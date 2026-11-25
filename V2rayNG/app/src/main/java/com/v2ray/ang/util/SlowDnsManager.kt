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
    const val SLOW_DNS_LOCAL_PORT = 10808

    /** false = format native VayDNS (server: tanpa -dnstt-compat, -clientid-size 2). */
    const val USE_DNSTT_COMPAT = false

    /** Harus sama dengan -clientid-size di server. */
    const val NATIVE_CLIENT_ID_SIZE = 2

    private const val DEFAULT_DOH = "https://cloudflare-dns.com/dns-query"
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
    private var gen = 0

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
            launchLocked(p)
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
        when {
            p.doh.isEmpty() -> cmd += listOf("-doh", DEFAULT_DOH)
            p.doh.startsWith("http", ignoreCase = true) -> cmd += listOf("-doh", p.doh)
            p.doh.startsWith("tls://", ignoreCase = true) -> cmd += listOf("-dot", p.doh.substring(6))
            else -> cmd += listOf("-udp", if (p.doh.contains(':')) p.doh else p.doh + ":53")
        }
        cmd += listOf("-domain", p.domain, "-pubkey-file", pubFile.absolutePath)
        cmd += listOf("-listen", "127.0.0.1:$SLOW_DNS_LOCAL_PORT")
        if (USE_DNSTT_COMPAT) cmd += "-dnstt-compat" else cmd += listOf("-clientid-size", NATIVE_CLIENT_ID_SIZE.toString())
        val rt = p.recordType.lowercase()
        if (rt.isNotEmpty() && rt != "otomatis" && rt != "auto") cmd += listOf("-record-type", rt)
        if (currentQname > 0) cmd += listOf("-max-qname-len", currentQname.toString())

        val floor = qnameFloor(p.domain, USE_DNSTT_COMPAT, NATIVE_CLIENT_ID_SIZE)
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

    /** Baca keluaran klien: teruskan ke log, kenali penolakan MTU dan timeout jabat tangan berulang. */
    private fun watch(pr: Process, myGen: Int, p: Params) {
        val startedAt = System.currentTimeMillis()
        var timeouts = 0
        try {
            pr.inputStream.bufferedReader().forEachLine { line ->
                TunnelLog.essential("dnstt: $line")
                if (tooSmall.containsMatchIn(line)) {
                    onTooSmall(myGen, p)
                } else if (timeoutWords.containsMatchIn(line) &&
                    System.currentTimeMillis() - startedAt < TIMEOUT_WINDOW_MS
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
            val k = key(p)
            val steps = qnameSteps(p.domain, USE_DNSTT_COMPAT, NATIVE_CLIENT_ID_SIZE)
            val next = nextQname(steps, currentQname, rejectedQname[k] ?: 0) ?: return
            learnedQname[k] = next
            TunnelLog.add("SlowDNS: jabat tangan timeout berulang, coba -max-qname-len $next")
            relaunchLocked(p, next)
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
