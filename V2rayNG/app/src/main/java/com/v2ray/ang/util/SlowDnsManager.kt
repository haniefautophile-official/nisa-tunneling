package com.v2ray.ang.util

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import kotlin.concurrent.thread

/**
 * Pengelola klien SlowDNS (libdnstt.so = vaydns-client).
 *
 * Klien membuka SOCKS/TCP lokal di 127.0.0.1:SLOW_DNS_LOCAL_PORT; Xray dan helper SSH
 * menyambung ke port itu (lihat CoreOutboundBuilder dan SshManager).
 *
 * Mode wire:
 *  - native VayDNS  (USE_DNSTT_COMPAT = false): -clientid-size NATIVE_CLIENT_ID_SIZE, overhead kecil.
 *  - dnstt compat   (USE_DNSTT_COMPAT = true) : -dnstt-compat, overhead lebih besar.
 * Server HARUS memakai mode dan clientid-size yang sama, kalau tidak jabat tangan gagal total.
 */
object SlowDnsManager {
    /** Port lokal tempat klien dnstt mendengarkan. */
    const val SLOW_DNS_LOCAL_PORT = 10899

    /**
     * true = format dnstt klasik (-dnstt-compat), cocok dengan server dnstt-server di produksi
     * (dnstt-server-null di port 5300). false = format native VayDNS (server: tanpa
     * -dnstt-compat, -clientid-size 2); jangan diaktifkan sebelum server diganti.
     */
    const val USE_DNSTT_COMPAT = true

    /** Harus sama dengan -clientid-size di server. */
    const val NATIVE_CLIENT_ID_SIZE = 2

    private const val DEFAULT_DOH = "https://1.1.1.1/dns-query"

    /** Batas tunggu balasan per query UDP (bawaan klien 500ms; balasan yang telat dibuang). */
    private const val UDP_TIMEOUT = "2s"

    /**
     * Jeda ulang pembuatan sesi setelah jabat tangan gagal. Bawaan klien 1s lalu berlipat dua sampai 30s,
     * ditambah 15s tunggu jabat tangan per percobaan: beberapa kegagalan berturut-turut = mati sekitar 2 menit.
     */
    private const val RECONNECT_MIN = "500ms"
    private const val RECONNECT_MAX = "4s"
    private const val MAX_QNAME = 253
    private const val NATIVE_OVERHEAD = 45
    private const val COMPAT_OVERHEAD = 64
    private const val PUBKEY_FILE = "slowdns_server.pub"

    /** Langkah -max-qname-len otomatis (selain default 0 dan lantai domain). */
    private val QNAME_CANDIDATES = listOf(80, 70)

    // Jabat tangan timeout berulang dalam jendela ini memicu turun satu langkah qname.
    private const val TIMEOUT_WINDOW_MS = 45_000L
    private const val TIMEOUTS_BEFORE_STEP = 3

    private val tooSmall = Regex("(?i)too small \\(minimum")
    private val timeoutWords = Regex("(?i)(handshake|timed? ?out|timeout)")

    private class Params(
        val context: Context,
        val domain: String,
        val pubkey: String,
        val doh: String,
        val recordType: String,
    ) {
        fun sameAs(o: Params) =
            domain == o.domain && pubkey == o.pubkey && doh == o.doh && recordType == o.recordType
    }

    private val lock = Any()
    @Volatile private var process: Process? = null
    private var params: Params? = null
    private var currentQname = 0
    // true kalau pengguna menetapkan qname=... di kolom DNS: tidak ada turun otomatis.
    private var fixedQname = false
    private var gen = 0

    // Type "Otomatis": probe tipe record yang lolos di resolver, lalu coba satu per satu sampai jabat tangan berhasil.
    private var autoMode = false
    private var autoCandidates: List<String> = emptyList()
    private var autoIndex = 0
    private var currentType = ""
    private val learnedType = HashMap<String, String>()
    private const val AUTO_MAX_CANDIDATES = 4
    private const val AUTO_TIMEOUT_WINDOW_MS = 90_000L

    private fun isAutoType(t: String): Boolean {
        val r = t.trim().lowercase()
        return r.isEmpty() || r == "otomatis" || r == "auto"
    }

    // Disimpan di memori per jaringan: qname yang berhasil dan batas yang ditolak klien (MTU terlalu kecil).
    private val learnedQname = HashMap<String, Int>()
    private val rejectedQname = HashMap<String, Int>()

    // ---------------------------------------------------------------- logika qname (murni, bisa di-test)

    private fun cleanDomain(domain: String) = domain.trim().trimEnd('.')

    /**
     * Batas bawah -max-qname-len supaya klien masih punya MTU yang cukup.
     * Native: domain + 45 + clientid (dns.nisavpn.biz.id, clientid 2 -> 65, clientid 1 -> 64).
     * Compat: domain + 64.
     */
    fun qnameFloor(domain: String, compat: Boolean, clientIdSize: Int = NATIVE_CLIENT_ID_SIZE): Int {
        val len = cleanDomain(domain).length
        return if (compat) len + COMPAT_OVERHEAD else len + NATIVE_OVERHEAD + clientIdSize
    }

    /**
     * Urutan nilai -max-qname-len yang dicoba, dari longgar ke ketat. 0 = bawaan klien (tanpa flag).
     * Nilai di bawah lantai dibuang, lantainya sendiri jadi langkah terakhir.
     * Domain yang terlalu panjang (lantai > 253) hanya punya langkah bawaan.
     */
    fun qnameSteps(domain: String, compat: Boolean, clientIdSize: Int = NATIVE_CLIENT_ID_SIZE): List<Int> {
        val floor = qnameFloor(domain, compat, clientIdSize)
        if (floor > MAX_QNAME) return listOf(0)
        return listOf(0) + QNAME_CANDIDATES.filter { it > floor } + floor
    }

    /**
     * Langkah berikutnya yang lebih ketat dari [current] (0 = bawaan) dan masih di atas [rejected]
     * (nilai yang pernah ditolak klien; 0 = belum ada). Null kalau sudah tidak ada langkah lagi.
     */
    fun nextQname(steps: List<Int>, current: Int, rejected: Int): Int? {
        val cur = if (current == 0) Int.MAX_VALUE else current
        return steps.filter { it != 0 && it < cur && it > rejected }.maxOrNull()
    }

    // ---------------------------------------------------------------- siklus proses

    fun isActive(): Boolean = try {
        process?.isAlive == true
    } catch (_: Exception) {
        false
    }

    fun start(context: Context, domain: String, pubkey: String, doh: String = "", recordType: String = "") {
        val p = Params(context.applicationContext, cleanDomain(domain), pubkey.trim(), doh.trim(), recordType.trim())
        synchronized(lock) {
            val old = params
            if (isActive() && old != null && old.sameAs(p)) return
            stopLocked()
            if (p.domain.isEmpty() || p.pubkey.isEmpty()) {
                TunnelLog.add("SlowDNS: domain atau public key kosong")
                return
            }
            params = p
            currentQname = learnedQname[key(p)] ?: 0
            autoMode = isAutoType(p.recordType)
            if (autoMode) startAutoLocked(p) else launchLocked(p)
        }
    }

    /**
     * Type "Otomatis": probe di thread latar (tidak menahan lock), lalu jalankan klien dengan tipe teratas.
     * Kalau start() dipanggil lagi atau stop() dipanggil selama probe, hasil probe dibuang (cek gen).
     */
    private fun startAutoLocked(p: Params) {
        val myGen = gen
        val k = key(p)
        val learned = learnedType[k]
        thread(isDaemon = true, name = "nisa-slowdns-probe") {
            val isUdp = p.doh.isNotEmpty() && !p.doh.startsWith("http", ignoreCase = true) &&
                !p.doh.startsWith("tls://", ignoreCase = true)
            val udpAddr = if (isUdp) SlowDnsUdpOptions.parse(p.doh, UDP_TIMEOUT).address else ""
            val ranked: List<String>
            if (udpAddr.isNotEmpty()) {
                val results = SlowDnsTypeProbe.probeAll(udpAddr, p.domain)
                TunnelLog.add("SlowDNS Otomatis: probe $udpAddr -> ${results.joinToString(" ")}")
                ranked = SlowDnsTypeProbe.rank(results, learned)
            } else {
                TunnelLog.add("SlowDNS Otomatis: resolver DoH/DoT/operator, probe dilewati")
                ranked = SlowDnsTypeProbe.ALL_TYPES
            }
            synchronized(lock) {
                if (myGen != gen) return@thread
                if (ranked.isEmpty()) {
                    TunnelLog.add("SlowDNS Otomatis: resolver tidak menjawab tipe record apa pun, tetap coba AAAA")
                }
                autoCandidates = ranked.ifEmpty { listOf(SlowDnsTypeProbe.PREFERRED_TYPE) }.take(AUTO_MAX_CANDIDATES)
                autoIndex = 0
                currentType = autoCandidates[0]
                learnedType[k] = currentType
                TunnelLog.add("SlowDNS Otomatis: mencoba ${currentType.uppercase()} (urutan ${autoCandidates.joinToString(",") { it.uppercase() }})")
                launchLocked(p)
            }
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        gen++
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
        params = null
    }

    private fun networkKey(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.toString() ?: "none"
    } catch (_: Exception) {
        "unknown"
    }

    private fun key(p: Params) = p.domain + "|" + networkKey(p.context)

    private fun launchLocked(p: Params) {
        launchedAt = System.currentTimeMillis()
        val binary = File(p.context.applicationInfo.nativeLibraryDir, "libdnstt.so")
        if (!binary.exists()) {
            TunnelLog.add("SlowDNS: gagal, libdnstt.so tidak ditemukan")
            params = null
            return
        }

        val pubFile = File(p.context.filesDir, PUBKEY_FILE)
        try {
            val asPath = File(p.pubkey)
            if (p.pubkey.startsWith("/") && asPath.isFile) {
                asPath.copyTo(pubFile, overwrite = true)
            } else {
                pubFile.writeText(p.pubkey + "\n")
            }
        } catch (e: Exception) {
            TunnelLog.add("SlowDNS: gagal menulis public key: ${e.message}")
            params = null
            return
        }

        val cmd = mutableListOf(binary.absolutePath)
        fixedQname = false
        var qnameOverride: Int? = null
        when {
            p.doh.isEmpty() -> cmd += listOf("-doh", DEFAULT_DOH)
            p.doh.startsWith("http", ignoreCase = true) -> cmd += listOf("-doh", p.doh)
            p.doh.startsWith("tls://", ignoreCase = true) -> cmd += listOf("-dot", p.doh.substring(6))
            else -> {
                // Mode UDP: "alamat[:port]" boleh diikuti "|rps=..,workers=..,timeout=..,shared=1" (lihat SlowDnsUdpOptions).
                val udp = SlowDnsUdpOptions.parse(p.doh, UDP_TIMEOUT)
                udp.warnings.forEach { TunnelLog.add("SlowDNS: $it") }
                if (udp.address.isEmpty()) {
                    TunnelLog.add("SlowDNS: alamat resolver kosong sebelum '|', pakai DoH bawaan")
                    cmd += listOf("-doh", DEFAULT_DOH)
                } else {
                    cmd += listOf("-udp", if (udp.address.contains(':')) udp.address else udp.address + ":53")
                    cmd += udp.flags
                    qnameOverride = udp.qname
                    TunnelLog.add("SlowDNS UDP: ${udp.summary}")
                }
            }
        }
        cmd += listOf("-domain", p.domain, "-pubkey-file", pubFile.absolutePath)
        cmd += listOf("-listen", "127.0.0.1:$SLOW_DNS_LOCAL_PORT")
        cmd += listOf("-reconnect-min", RECONNECT_MIN, "-reconnect-max", RECONNECT_MAX)
        if (USE_DNSTT_COMPAT) cmd += "-dnstt-compat" else cmd += listOf("-clientid-size", NATIVE_CLIENT_ID_SIZE.toString())
        val rt = if (autoMode) currentType else p.recordType.trim().lowercase()
        if (rt.isNotEmpty() && !isAutoType(rt)) cmd += listOf("-record-type", rt)
        val floor = qnameFloor(p.domain, USE_DNSTT_COMPAT, NATIVE_CLIENT_ID_SIZE)
        if (qnameOverride != null) {
            val q = qnameOverride.coerceIn(floor.coerceAtMost(MAX_QNAME), MAX_QNAME)
            if (q != qnameOverride) TunnelLog.add("SlowDNS: qname $qnameOverride di-clamp ke $q (lantai $floor)")
            fixedQname = true
            currentQname = q
        }
        if (currentQname > 0) cmd += listOf("-max-qname-len", currentQname.toString())

        TunnelLog.detail(
            "SlowDNS: ${p.domain} mode=${if (USE_DNSTT_COMPAT) "compat" else "native"} " +
                "qname=${if (currentQname == 0) "default" else currentQname.toString()} lantai=$floor"
        )

        try {
            val pr = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process = pr
            val myGen = gen
            thread(isDaemon = true, name = "nisa-slowdns-out") { watch(pr, myGen, p) }
        } catch (e: Exception) {
            TunnelLog.add("SlowDNS: gagal menjalankan klien: ${e.message}")
            process = null
            params = null
        }
    }

    // Baris dnstt yang hanya bising: tiap stream (tiap koneksi aplikasi) mencetak "stream ... ready/closed".
    private val dnsttStreamNoise = Regex("""msg="stream \S+ (ready|closed|end|done)""")
    private val dnsttFields = Regex("""level=(\w+) msg="(.*)"\s*$""")
    private val dnsttUnreachable = Regex("(?i)network is unreachable")
    @Volatile private var lastUnreachableLog = 0L

    /**
     * Rapikan satu baris keluaran dnstt untuk tab LOG: buang baris stream, buang cap waktu bawaan dnstt
     * (log sudah punya jam sendiri) dan awalan level info; error jaringan yang berulang tiap detik
     * ("network is unreachable") dicatat paling sering sekali per 15 detik. Null = jangan dicatat.
     */
    private fun tidyDnsttLine(line: String): String? {
        if (dnsttStreamNoise.containsMatchIn(line)) return null
        if (dnsttUnreachable.containsMatchIn(line)) {
            val now = System.currentTimeMillis()
            if (now - lastUnreachableLog < 15_000L) return null
            lastUnreachableLog = now
            return "jaringan HP tidak terjangkau (sendto: network is unreachable), menunggu jaringan kembali"
        }
        val m = dnsttFields.find(line) ?: return line
        val level = m.groupValues[1]
        val msg = m.groupValues[2]
        return if (level == "info") msg else "$level: $msg"
    }

    /** Baca keluaran klien: teruskan ke log, kenali penolakan MTU dan timeout jabat tangan berulang. */
    private fun watch(pr: Process, myGen: Int, p: Params) {
        val startedAt = System.currentTimeMillis()
        var timeouts = 0
        try {
            pr.inputStream.bufferedReader().forEachLine { line ->
                tidyDnsttLine(line)?.let { TunnelLog.essential("dnstt: $it") }
                if (tooSmall.containsMatchIn(line)) {
                    onTooSmall(myGen, p)
                } else if (timeoutWords.containsMatchIn(line) &&
                    System.currentTimeMillis() - startedAt < (if (autoMode) AUTO_TIMEOUT_WINDOW_MS else TIMEOUT_WINDOW_MS)
                ) {
                    timeouts++
                    if (timeouts >= TIMEOUTS_BEFORE_STEP) {
                        timeouts = 0
                        stepDown(myGen, p)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Klien menolak qname sekarang (MTU terlalu kecil): ingat batasnya dan kembali ke bawaan. */
    private fun onTooSmall(myGen: Int, p: Params) {
        synchronized(lock) {
            if (myGen != gen) return
            if (fixedQname) {
                TunnelLog.add("SlowDNS: qname $currentQname ditolak klien (MTU terlalu kecil); naikkan qname atau hapus opsinya")
                return
            }
            val k = key(p)
            rejectedQname[k] = currentQname
            learnedQname.remove(k)
            TunnelLog.add("SlowDNS: qname $currentQname ditolak klien (MTU terlalu kecil), kembali ke bawaan")
            relaunchLocked(p, 0)
        }
    }

    private fun stepDown(myGen: Int, p: Params) {
        synchronized(lock) {
            if (myGen != gen) return
            if (autoMode && autoIndex + 1 < autoCandidates.size) {
                // Type Otomatis: tipe ini tidak berhasil jabat tangan, coba tipe berikutnya.
                autoIndex++
                currentType = autoCandidates[autoIndex]
                learnedType[key(p)] = currentType
                TunnelLog.add("SlowDNS Otomatis: jabat tangan gagal, coba tipe ${currentType.uppercase()}")
                relaunchLocked(p, currentQname)
                return
            }
            if (fixedQname) return
            val k = key(p)
            val steps = qnameSteps(p.domain, USE_DNSTT_COMPAT, NATIVE_CLIENT_ID_SIZE)
            val next = nextQname(steps, currentQname, rejectedQname[k] ?: 0) ?: run {
                if (autoMode) {
                    learnedType.remove(k)
                    TunnelLog.add("SlowDNS Otomatis: semua tipe dan qname sudah dicoba, jabat tangan tetap gagal; cek tipe record di server")
                }
                return
            }
            learnedQname[k] = next
            if (autoMode && autoCandidates.isNotEmpty()) {
                autoIndex = 0
                currentType = autoCandidates[0]
            }
            TunnelLog.add("SlowDNS: jabat tangan timeout berulang, coba -max-qname-len $next")
            relaunchLocked(p, next)
        }
    }

    @Volatile private var lastWatchdogRestart = 0L
    @Volatile private var launchedAt = 0L

    /**
     * Watchdog: dipanggil AutoPing saat ping gagal berturut-turut. Memulai ulang proses dnstt dengan
     * qname/tipe yang sama; proses baru memakai port sumber UDP baru, jadi pemetaan NAT yang basi terlepas.
     * Jeda minimal 60 detik antar restart supaya tidak berputar-putar.
     */
    fun restartStale(): Boolean {
        synchronized(lock) {
            val p = params ?: return false
            if (!isActive()) return false
            val now = System.currentTimeMillis()
            // Masa tenggang: jabat tangan Noise dan login SSH lewat tunnel DNS butuh belasan detik; ping yang
            // gagal di masa itu belum berarti tunnel macet, jadi jangan dimulai ulang.
            if (now - launchedAt < 60_000L) return false
            if (now - lastWatchdogRestart < 60_000L) return false
            lastWatchdogRestart = now
            TunnelLog.add("SlowDNS: ping gagal berulang, dnstt dimulai ulang (port sumber baru)")
            relaunchLocked(p, currentQname)
            return true
        }
    }

    /** Mulai ulang dnstt tanpa masa tenggang (dipakai pemulihan mode pesawat: socket UDP lama sudah mati). */
    fun forceRestart(): Boolean {
        synchronized(lock) {
            val p = params ?: return false
            if (!isActive()) return false
            lastWatchdogRestart = System.currentTimeMillis()
            TunnelLog.add("SlowDNS: dnstt dimulai ulang setelah mode pesawat")
            relaunchLocked(p, currentQname)
            return true
        }
    }

    private fun relaunchLocked(p: Params, qname: Int) {
        gen++
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
        currentQname = qname
        params = p
        launchLocked(p)
    }
}
