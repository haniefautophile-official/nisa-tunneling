package com.v2ray.ang.util

import android.content.Context
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.concurrent.thread

object SlowDnsManager {
    private const val TAG = "NisaLog" // tag ini yang ditampilkan menu Logcat aplikasi
    private var process: Process? = null

    // Parameter proses dnstt yang sedang jalan. Dipakai untuk mendeteksi pergantian profil.
    private var currentKey: String? = null

    // Jangan 10808: itu port SOCKS bawaan aplikasi (AppConfig.PORT_SOCKS).
    const val SLOW_DNS_LOCAL_PORT = 10899

    // Tipe record yang dikenal klien dnstt (libdnstt.so). Di luar daftar ini jatuh ke TXT.
    private val SUPPORTED_TYPES = setOf("txt", "null", "cname", "mx", "ns", "srv", "a", "aaaa", "caa")

    // NISA_QNAME_V3: satu timeout langsung memicu turun batas.
    private const val HANDSHAKE_FAILS_BEFORE_STEP = 1

    private const val USE_DNSTT_COMPAT = false
    private const val NATIVE_CLIENT_ID_SIZE = 1

    private const val QNAME_MAX = 253
    // NISA_QNAME_V3: langkah jauh di bawah versi lama. XL membuang query data di sekitar 49
    // karakter, jadi 130 dan 100 selalu ditolak. Langkah baru mulai dari 80 turun ke 40 supaya
    // domain pendek masih punya ruang handshake setelah base32 + ClientID.
    private val QNAME_STEPS = intArrayOf(80, 70, 60, 55, 50, 45, 40)

    /**
     * Urutan batas -max-qname-len dari yang terbesar: 0 = bawaan klien, lalu 80, 70, 60, 55,
     * 50, 45, 40, dan batas terkecil yang masih diizinkan. Lantai (floor) beda per mode wire format:
     *  - compat: ClientID 8 byte + padding (overhead 13 byte) -> lantai domain + 61, dibulatkan 64.
     *  - native: ClientID 1 byte (overhead 2 byte) -> lantai domain + 43, dibulatkan 45.
     * NISA_QNAME_V3: lantai dihitung dari panjang domain nyata, jadi domain panjang seperti
     * xray.nisavpn.biz.id (21 karakter) menghasilkan lantai 66 dan langkah 45/40 otomatis dibuang.
     * Kalau semua langkah di bawah lantai, hanya 0 yang tersisa dan turun QNAME tidak akan menolong.
     */
    internal fun qnameSteps(domain: String, compat: Boolean = USE_DNSTT_COMPAT): List<Int> {
        val floor = domain.trim().length + if (compat) 64 else 45
        if (floor > QNAME_MAX) return listOf(0)
        return (listOf(0) + QNAME_STEPS.toList() + floor)
            .filter { it == 0 || (it >= floor && it < QNAME_MAX) }
            .distinct()
    }

    /** Batas berikutnya yang lebih kecil dari [current] dan lebih besar dari [rejected], atau null. */
    internal fun nextQname(steps: List<Int>, current: Int, rejected: Int): Int? =
        steps.firstOrNull { it != 0 && (current == 0 || it < current) && it > rejected }

    private fun isHandshakeTimeout(line: String): Boolean =
        line.contains("noise handshake", ignoreCase = true) && line.contains("timeout", ignoreCase = true)

    // NISA_QNAME_V3: klien kadang keluar dengan "effective MTU" < 48 atau "MTU too small" atau
    // "mtu rejected". Semua bentuk itu harus dianggap penolakan supaya batas QNAME tidak diulang.
    private fun isMtuTooSmall(line: String): Boolean {
        val lower = line.lowercase()
        if (lower.contains("mtu") && (lower.contains("too small") || lower.contains("reject") || lower.contains("below"))) return true
        val m = Regex("effective\\s+mtu\\s*[=:]?\\s*(\\d+)", RegexOption.IGNORE_CASE).find(line) ?: return false
        val mtu = m.groupValues[1].toIntOrNull() ?: return false
        return mtu < 48
    }

    private class LaunchArgs(
        val context: Context,
        val domain: String,
        val type: String,
        val key: String,
        val resolver: List<String>,
        val netKey: String,
        val pubkeyPath: String
    )

    // Disimpan supaya klien bisa dijalankan ulang dengan batas nama query lain.
    private var launchArgs: LaunchArgs? = null
    private var currentQname = 0
    private var rejectedQname = 0
    private var handshakeFails = 0

    // Batas yang terakhir dipakai per jaringan (domain + alamat DNS), supaya sambung ulang di
    // jaringan yang sama langsung memakai batas yang sudah terbukti perlu. Hanya di memori.
    private val learnedQname = HashMap<String, Int>()

    @Synchronized
    fun start(context: Context, domain: String, pubkey: String, doh: String, recordType: String = "") {
        TunnelLog.init(context)
        val requested = recordType.trim().lowercase().takeUnless { it.isEmpty() || it == "otomatis" } ?: "txt"
        val type = if (requested in SUPPORTED_TYPES) requested else {
            TunnelLog.add("SlowDNS: tipe record '$requested' tidak dikenal, memakai txt")
            "txt"
        }
        val key = "${domain.trim()}|${pubkey.trim()}|${doh.trim()}|$type"
        if (process?.isAlive == true) {
            if (key == currentKey) return
            TunnelLog.detail("SlowDNS: profil berganti, dnstt lama dimatikan")
            stopLocked()
        }
        process = null
        currentKey = null
        launchArgs = null

        val binaryPath = File(context.applicationInfo.nativeLibraryDir, "libdnstt.so").absolutePath
        if (!File(binaryPath).exists()) {
            TunnelLog.add("SlowDNS: libdnstt.so tidak ada di APK")
            return
        }

        val pubkeyFile = File(context.filesDir, "dnstt_pubkey_temp.txt")
        try {
            pubkeyFile.writeText(pubkey.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menulis pubkey", e)
            TunnelLog.add("SlowDNS: gagal menulis pubkey: ${e.message}")
            return
        }

        val resolver = resolverArgs(context, doh)
        val args = LaunchArgs(
            context = context.applicationContext,
            domain = domain.trim(),
            type = type,
            key = key,
            resolver = resolver,
            netKey = "${domain.trim()}|${resolver.joinToString(" ")}",
            pubkeyPath = pubkeyFile.absolutePath
        )
        launchArgs = args
        rejectedQname = 0
        TunnelLog.add("SlowDNS: tipe record $type, DNS ${resolver.joinToString(" ")} (isian profil: ${doh.trim().ifEmpty { "kosong" }})")
        launchLocked(args, learnedQname[args.netKey] ?: 0)
    }

    /** Menjalankan klien dnstt. [qname] 0 = batas nama query bawaan klien. Dipanggil dengan lock dipegang. */
    private fun launchLocked(args: LaunchArgs, qname: Int) {
        val binaryPath = File(args.context.applicationInfo.nativeLibraryDir, "libdnstt.so").absolutePath
        val command = mutableListOf(binaryPath)
        if (USE_DNSTT_COMPAT) {
            command += listOf("-dnstt-compat")
        } else {
            command += listOf("-clientid-size", NATIVE_CLIENT_ID_SIZE.toString())
        }
        command += listOf("-record-type", args.type)
        command += args.resolver
        if (qname > 0) command += listOf("-max-qname-len", qname.toString())
        command += listOf(
            "-pubkey-file", args.pubkeyPath,
            "-domain", args.domain,
            "-listen", "127.0.0.1:$SLOW_DNS_LOCAL_PORT"
        )
        currentQname = qname
        handshakeFails = 0
        if (qname > 0) TunnelLog.add("SlowDNS: batas nama query $qname (lebih kecil dari bawaan, tunnel lebih lambat)")

        try {
            TunnelLog.detail("SlowDNS: memulai ${args.domain}")
            val pb = ProcessBuilder(command).redirectErrorStream(true)
            pb.environment()["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
            val p = pb.start()
            process = p
            currentKey = args.key
            // NISA_QNAME_V3: pembaca pipa hanya mencatat flag. Tidak memanggil stopLocked() dari
            // thread ini, karena itu menutup inputStream yang sedang dibaca dan memunculkan
            // "read/write on closed pipe". Evaluasi dilakukan di thread terpisah setelah proses
            // benar-benar berhenti.
            thread(isDaemon = true, name = "dnstt-log") {
                var mtuRejected = false
                var handshakeTimedOut = false
                try {
                    p.inputStream.bufferedReader().forEachLine {
                        Log.i(TAG, it)
                        if (it.contains("level=warn") || it.contains("level=error")) TunnelLog.add("SlowDNS: $it")
                        else TunnelLog.essential("SlowDNS: $it")
                        if (isMtuTooSmall(it)) {
                            mtuRejected = true
                        } else if (isHandshakeTimeout(it)) {
                            handshakeTimedOut = true
                        }
                    }
                } catch (_: Exception) {
                }
                Log.w(TAG, "dnstt berhenti")
                TunnelLog.detail("SlowDNS: berhenti")
                if (mtuRejected) {
                    thread(isDaemon = true, name = "dnstt-qname-reject") { onQnameRejected(p) }
                } else if (handshakeTimedOut) {
                    thread(isDaemon = true, name = "dnstt-qname-step") { onHandshakeTimeout(p) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menjalankan dnstt", e)
            TunnelLog.add("SlowDNS: gagal menjalankan: ${e.message}")
        }
    }

    /**
     * NISA_QNAME_V3: jabat tangan timeout. Proses lama dimatikan dulu (destroy + tunggu keluar)
     * tanpa memanggil stopLocked() dari pembaca pipa, lalu launch ulang dengan batas lebih kecil.
     */
    @Synchronized
    private fun onHandshakeTimeout(p: Process) {
        if (process !== p) return
        handshakeFails++
        if (handshakeFails < HANDSHAKE_FAILS_BEFORE_STEP) return
        val args = launchArgs ?: return
        val next = nextQname(qnameSteps(args.domain), currentQname, rejectedQname)
        if (next == null) {
            if (handshakeFails == HANDSHAKE_FAILS_BEFORE_STEP) {
                TunnelLog.add("SlowDNS: jabat tangan tetap timeout di batas nama query terkecil (lantai domain ${args.domain.length + 45}). Coba domain tunnel lebih pendek atau ganti transport (WS/TLS port 443)")
            }
            return
        }
        TunnelLog.add("SlowDNS: jabat tangan timeout $handshakeFails kali, mencoba batas nama query $next")
        learnedQname[args.netKey] = next
        // Matikan proses lama tanpa stopLocked(): stopLocked() menutup process field, tapi proses
        // ini sudah berhenti sendiri (pipa habis), jadi cukup destroy untuk memastikan.
        try { p.destroy() } catch (_: Exception) { }
        var waited = 0
        while (waited < 1500) {
            val exited = try { p.exitValue(); true } catch (_: IllegalThreadStateException) { false }
            if (exited) break
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            waited += 50
        }
        if (process === p) {
            process = null
            currentKey = null
        }
        launchLocked(args, next)
    }

    /** Klien menolak batas ini (MTU di bawah minimum): kembali ke batas sebelumnya dan jangan turun lagi. */
    @Synchronized
    private fun onQnameRejected(p: Process) {
        if (process !== p) return
        val args = launchArgs ?: return
        val rejected = currentQname
        if (rejected == 0) return
        rejectedQname = rejected
        val back = qnameSteps(args.domain).lastOrNull { it == 0 || it > rejected } ?: 0
        learnedQname[args.netKey] = back
        TunnelLog.add("SlowDNS: batas nama query $rejected ditolak klien, kembali ke ${if (back == 0) "bawaan" else back.toString()}")
        try { p.destroy() } catch (_: Exception) { }
        var waited = 0
        while (waited < 1500) {
            val exited = try { p.exitValue(); true } catch (_: IllegalThreadStateException) { false }
            if (exited) break
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            waited += 50
        }
        if (process === p) {
            process = null
            currentKey = null
        }
        launchLocked(args, back)
    }

    /** Binary Go di Android tidak punya DNS sendiri, jadi nama host di-resolve di sini. */
    private fun resolveHost(host: String): String {
        if (host.contains(":") || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return host
        var result: String? = null
        val t = thread(isDaemon = true, name = "slowdns-resolve") {
            try {
                val all = InetAddress.getAllByName(host)
                result = (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress
            } catch (_: Exception) {
            }
        }
        t.join(5000)
        val ip = result
        if (ip == null) TunnelLog.add("SlowDNS: gagal resolve $host")
        return ip ?: host
    }

    private val KNOWN_RESOLVER_IPS = mapOf(
        "dns.google" to "8.8.8.8",
        "cloudflare-dns.com" to "1.1.1.1",
        "one.one.one.one" to "1.1.1.1",
        "dns.quad9.net" to "9.9.9.9"
    )

    private fun hostToIp(host: String): String =
        KNOWN_RESOLVER_IPS[host.lowercase()] ?: resolveHost(host)

    private fun dohUrlWithIp(url: String): String {
        val m = Regex("^(https://)([^/:\\[]+)(.*)$", RegexOption.IGNORE_CASE).find(url) ?: return url
        val host = m.groupValues[2]
        if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return url
        return m.groupValues[1] + hostToIp(host) + m.groupValues[3]
    }

    private fun systemResolverArgs(context: Context): List<String> {
        val fallback = listOf("-doh", "https://1.1.1.1/dns-query")
        val chosen: String? = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm == null) null else {
                val found = mutableListOf<Pair<Int, String>>()
                for (n in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                    if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                    val ip = cm.getLinkProperties(n)?.dnsServers.orEmpty()
                        .filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: continue
                    val rank = when {
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> 0
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                        else -> 2
                    }
                    found += rank to ip
                }
                found.minByOrNull { it.first }?.second
            }
        } catch (e: Exception) {
            TunnelLog.add("SlowDNS: gagal membaca DNS sistem: ${e.message}")
            null
        }
        if (chosen == null) {
            TunnelLog.add("SlowDNS: DNS sistem tidak ditemukan, memakai DoH 1.1.1.1")
            return fallback
        }
        TunnelLog.detail("SlowDNS: DNS sistem $chosen")
        return listOf("-udp", "$chosen:53")
    }

    private fun withPort(hostPort: String, defaultPort: Int): String =
        if (Regex(".*:\\d+$").matches(hostPort)) hostPort else "$hostPort:$defaultPort"

    private fun resolverArgs(context: Context, raw: String): List<String> {
        val r = raw.trim()
        if (r.isEmpty()) return systemResolverArgs(context)
        return when {
            r.equals("sistem", ignoreCase = true) || r.equals("system", ignoreCase = true) -> systemResolverArgs(context)
            r.startsWith("https://", ignoreCase = true) -> listOf("-doh", dohUrlWithIp(r))
            r.startsWith("tls://", ignoreCase = true) || r.startsWith("dot://", ignoreCase = true) ->
                withPort(r.substringAfter("://"), 853).let { hp ->
                    listOf("-dot", "${hostToIp(hp.substringBeforeLast(":"))}:${hp.substringAfterLast(":")}")
                }
            else -> {
                val hostPort = withPort(r.removePrefix("udp://").removePrefix("UDP://"), 53)
                val host = hostPort.substringBeforeLast(":")
                val port = hostPort.substringAfterLast(":")
                listOf("-udp", "${resolveHost(host)}:$port")
            }
        }
    }

    fun isActive(): Boolean = process?.isAlive == true

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        val p = process
        process = null
        currentKey = null
        if (p == null) return
        try { p.destroy() } catch (_: Exception) { }
        var waited = 0
        while (waited < 1500) {
            val exited = try { p.exitValue(); true } catch (_: IllegalThreadStateException) { false }
            if (exited) break
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            waited += 50
        }
    }
}
