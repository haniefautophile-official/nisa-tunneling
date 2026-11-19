package com.v2ray.ang.util

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

object SlowDnsManager {
    private const val TAG = "NisaLog" // tag ini yang ditampilkan menu Logcat aplikasi
    private var process: Process? = null

    // Parameter proses dnstt yang sedang jalan. Dipakai untuk mendeteksi pergantian profil.
    private var currentKey: String? = null

    // Jangan 10808: itu port SOCKS bawaan aplikasi (AppConfig.PORT_SOCKS).
    const val SLOW_DNS_LOCAL_PORT = 10899

    @Synchronized
    fun start(context: Context, domain: String, pubkey: String, doh: String) {
        TunnelLog.init(context)
        val key = "${domain.trim()}|${pubkey.trim()}|${doh.trim()}"
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

        val command = listOf(
            binaryPath,
            "-doh", doh.ifEmpty { "https://cloudflare-dns.com/dns-query" },
            "-pubkey-file", pubkeyFile.absolutePath,
            domain.trim(),
            "127.0.0.1:$SLOW_DNS_LOCAL_PORT"
        )

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
