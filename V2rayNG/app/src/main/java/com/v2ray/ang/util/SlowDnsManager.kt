package com.v2ray.ang.util

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

object SlowDnsManager {
    private const val TAG = "NisaLog" // tag ini yang ditampilkan menu Logcat aplikasi
    private var process: Process? = null

    // Jangan 10808: itu port SOCKS bawaan aplikasi (AppConfig.PORT_SOCKS).
    const val SLOW_DNS_LOCAL_PORT = 10899

    @Synchronized
    fun start(context: Context, domain: String, pubkey: String, doh: String) {
        TunnelLog.init(context)
        // Kalau proses lama sudah mati, boleh start ulang.
        if (process?.isAlive == true) return
        process = null

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
            TunnelLog.add("SlowDNS: memulai ${domain.trim()}")
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            process = p
            // Wajib dibaca terus: kalau tidak, buffer pipa penuh dan dnstt macet.
            // Sekaligus menampilkan error/usage dnstt di logcat (tag SlowDnsManager).
            thread(isDaemon = true, name = "dnstt-log") {
                try {
                    p.inputStream.bufferedReader().forEachLine { Log.i(TAG, it); TunnelLog.add("SlowDNS: $it") }
                } catch (_: Exception) {
                }
                Log.w(TAG, "dnstt berhenti")
                TunnelLog.add("SlowDNS: berhenti")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menjalankan dnstt", e)
            TunnelLog.add("SlowDNS: gagal menjalankan: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
    }
}
