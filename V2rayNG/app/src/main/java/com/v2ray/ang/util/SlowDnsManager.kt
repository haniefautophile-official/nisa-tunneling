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

    @Synchronized
    fun start(context: Context, domain: String, pubkey: String, doh: String, recordType: String = "") {
        TunnelLog.init(context)
        val type = recordType.trim().lowercase().takeUnless { it.isEmpty() || it == "otomatis" } ?: "txt"
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

        // Klien VayDNS (libdnstt.so) dalam mode kompatibel dnstt, supaya cocok dengan server dnstt.
        val command = mutableListOf(binaryPath, "-dnstt-compat", "-record-type", type)
        val resolver = resolverArgs(doh)
        command += resolver
        command += listOf(
            "-pubkey-file", pubkeyFile.absolutePath,
            "-domain", domain.trim(),
            "-listen", "127.0.0.1:$SLOW_DNS_LOCAL_PORT"
        )
        TunnelLog.detail("SlowDNS: tipe record $type, resolver ${resolver.joinToString(" ")}")

        try {
            TunnelLog.detail("SlowDNS: memulai ${domain.trim()}")
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            process = p
            currentKey = key
            // Wajib dibaca terus: kalau tidak, buffer pipa penuh dan dnstt macet.
            // Sekaligus menampilkan error/usage dnstt di logcat (tag SlowDnsManager).
            thread(isDaemon = true, name = "dnstt-log") {
                try {
                    p.inputStream.bufferedReader().forEachLine { Log.i(TAG, it); TunnelLog.essential("SlowDNS: $it") }
                } catch (_: Exception) {
                }
                Log.w(TAG, "dnstt berhenti")
                TunnelLog.detail("SlowDNS: berhenti")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menjalankan dnstt", e)
            TunnelLog.add("SlowDNS: gagal menjalankan: ${e.message}")
        }
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

    private fun withPort(hostPort: String, defaultPort: Int): String =
        if (Regex(".*:\\d+$").matches(hostPort)) hostPort else "$hostPort:$defaultPort"

    /** Kosong = DoH Cloudflare (perilaku lama). https:// = DoH, tls:// atau dot:// = DoT, lainnya = UDP host[:port]. */
    private fun resolverArgs(raw: String): List<String> {
        val r = raw.trim()
        if (r.isEmpty()) return listOf("-doh", "https://cloudflare-dns.com/dns-query")
        return when {
            r.startsWith("https://", ignoreCase = true) -> listOf("-doh", r)
            r.startsWith("tls://", ignoreCase = true) || r.startsWith("dot://", ignoreCase = true) ->
                listOf("-dot", withPort(r.substringAfter("://"), 853))
            else -> {
                val hostPort = withPort(r.removePrefix("udp://").removePrefix("UDP://"), 53)
                val host = hostPort.substringBeforeLast(":")
                val port = hostPort.substringAfterLast(":")
                listOf("-udp", "${resolveHost(host)}:$port")
            }
        }
    }

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
