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

    // Pada operator tertentu (terlihat di XL) resolver hanya meneruskan query polling yang pendek
    // (nama 47 karakter) dan membuang query berisi data yang namanya panjang, sehingga jabat tangan
    // Noise selalu timeout. Klien mode dnstt-compat memakai nama sampai 253 karakter, jadi bila
    // jabat tangan timeout berulang, batas panjang nama (-max-qname-len) diturunkan bertahap.
    // Hapus bila server/klien tidak lagi dipakai lewat resolver yang membuang query panjang.
    private const val HANDSHAKE_FAILS_BEFORE_STEP = 2

    // Sejak 2.4.25: klien dipakai dalam mode wire native VayDNS (bukan -dnstt-compat), karena
    // overhead per-query jauh lebih kecil (ClientID 1 byte + 1 byte panjang, vs 8+1+3+1 byte
    // di mode compat). Server (dnstt-server-multi / vaydns-server) WAJIB dijalankan dengan
    // -clientid-size 1 dan TANPA -dnstt-compat, atau jabat tangan gagal total (bukan lambat).
    // Set true cuma kalau perlu balik ke server dnstt-compat lama.
    private const val USE_DNSTT_COMPAT = false
    private const val NATIVE_CLIENT_ID_SIZE = 1

    private const val QNAME_MAX = 253
    private val QNAME_STEPS = intArrayOf(130, 100)

    /**
     * Urutan batas -max-qname-len dari yang terbesar: 0 = bawaan klien, lalu 130, 100, dan batas
     * terkecil yang masih diizinkan. Lantai (floor) beda per mode wire format karena overhead
     * per-query beda jauh:
     *  - compat: ClientID 8 byte + padding (overhead 13 byte) -> lantai domain + 61, dibulatkan 64.
     *  - native: ClientID 1 byte (overhead 2 byte) -> lantai domain + 43, dibulatkan 45.
     * Keduanya masih dibatasi lantai KCP sendiri (~24 byte minimum per paket), jadi ini bukan
     * batas bebas-pilih, cuma titik terendah yang masih bisa dipakai klien.
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

    private fun isMtuTooSmall(line: String): Boolean =
        line.contains("MTU", ignoreCase = false) && line.contains("too small", ignoreCase = true)

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
            // Parameter sama: pakai terus dnstt yang sudah jalan (perilaku lama, aman untuk SSH).
            if (key == currentKey) return
            // Parameter beda (pindah profil): matikan dnstt lama, kalau tidak profil baru
            // akan masuk ke tunnel domain/pubkey lama dan semua koneksi gagal.
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
        // Klien VayDNS (libdnstt.so). Mode native (bukan -dnstt-compat) dipakai sejak 2.4.25 karena
        // overhead per-query jauh lebih kecil; server WAJIB dipasangkan dengan -clientid-size 1 dan
        // TANPA -dnstt-compat, kalau tidak jabat tangan gagal total.
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
            // Klien Go dibuild untuk linux, jadi tidak tahu lokasi CA Android. Tanpa ini DoH/DoT gagal
            // dengan "x509: certificate signed by unknown authority".
            pb.environment()["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
            val p = pb.start()
            process = p
            currentKey = args.key
            // Wajib dibaca terus: kalau tidak, buffer pipa penuh dan dnstt macet.
            // Sekaligus menampilkan error/usage dnstt di logcat (tag SlowDnsManager).
            thread(isDaemon = true, name = "dnstt-log") {
                var mtuRejected = false
                try {
                    p.inputStream.bufferedReader().forEachLine {
                        Log.i(TAG, it)
                        // Peringatan/error klien (mis. tipe record dipaksa ke TXT) selalu ditampilkan.
                        if (it.contains("level=warn") || it.contains("level=error")) TunnelLog.add("SlowDNS: $it")
                        else TunnelLog.essential("SlowDNS: $it")
                        if (isMtuTooSmall(it)) {
                            mtuRejected = true
                        } else if (isHandshakeTimeout(it)) {
                            onHandshakeTimeout(p)
                        }
                    }
                } catch (_: Exception) {
                }
                Log.w(TAG, "dnstt berhenti")
                TunnelLog.detail("SlowDNS: berhenti")
                if (mtuRejected) onQnameRejected(p)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menjalankan dnstt", e)
            TunnelLog.add("SlowDNS: gagal menjalankan: ${e.message}")
        }
    }

    /** Jabat tangan timeout lagi: setelah beberapa kali, jalankan ulang dengan batas nama query lebih kecil. */
    @Synchronized
    private fun onHandshakeTimeout(p: Process) {
        if (process !== p) return // proses ini sudah diganti atau dihentikan
        handshakeFails++
        if (handshakeFails < HANDSHAKE_FAILS_BEFORE_STEP) return
        val args = launchArgs ?: return
        val next = nextQname(qnameSteps(args.domain), currentQname, rejectedQname)
        if (next == null) {
            if (handshakeFails == HANDSHAKE_FAILS_BEFORE_STEP) {
                TunnelLog.add("SlowDNS: jabat tangan tetap timeout di batas nama query terkecil. Periksa server, tipe record, atau coba DNS lain di profil")
            }
            return
        }
        TunnelLog.add("SlowDNS: jabat tangan timeout $handshakeFails kali, mencoba batas nama query $next")
        learnedQname[args.netKey] = next
        stopLocked()
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
        stopLocked()
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

    /** Penyedia DNS umum: sertifikatnya memuat alamat IP, jadi aman dipanggil lewat IP. */
    private val KNOWN_RESOLVER_IPS = mapOf(
        "dns.google" to "8.8.8.8",
        "cloudflare-dns.com" to "1.1.1.1",
        "one.one.one.one" to "1.1.1.1",
        "dns.quad9.net" to "9.9.9.9"
    )

    private fun hostToIp(host: String): String =
        KNOWN_RESOLVER_IPS[host.lowercase()] ?: resolveHost(host)

    /** Klien Go di Android tidak bisa resolve nama, jadi host pada URL DoH diganti alamat IP-nya. */
    private fun dohUrlWithIp(url: String): String {
        val m = Regex("^(https://)([^/:\\[]+)(.*)$", RegexOption.IGNORE_CASE).find(url) ?: return url
        val host = m.groupValues[2]
        if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return url
        return m.groupValues[1] + hostToIp(host) + m.groupValues[3]
    }

    /**
     * "sistem": pakai DNS resolver milik jaringan yang sedang dipakai HP (operator atau Wi-Fi),
     * di luar VPN. Operator seluler biasanya hanya meneruskan UDP 53 ke resolvernya sendiri.
     */
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
                    // Wi-Fi didahulukan (itu jalur utama kalau tersambung), lalu seluler, lalu lainnya.
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

    /** Kosong atau "sistem" = DNS resolver operator/jaringan HP lewat UDP 53 (jalan tanpa kuota). Fallback DoH 1.1.1.1 hanya jika DNS sistem tidak ditemukan. https:// = DoH, tls:// atau dot:// = DoT, lainnya = UDP host[:port]. */
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

    /** True bila klien dnstt sedang jalan (dipakai AutoPing untuk memberi batas waktu lebih longgar). */
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
        // Tunggu sebentar sampai proses benar-benar keluar agar port lokal bebas lagi.
        var waited = 0
        while (waited < 1500) {
            val exited = try { p.exitValue(); true } catch (_: IllegalThreadStateException) { false }
            if (exited) break
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            waited += 50
        }
    }
}
