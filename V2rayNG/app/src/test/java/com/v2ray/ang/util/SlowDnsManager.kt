package com.v2ray.ang.util

import android.content.Context
import java.io.File

object SlowDnsManager {
    private var process: Process? = null
    const val SLOW_DNS_LOCAL_PORT = 10808 // Port lokal untuk Xray

    fun start(context: Context, domain: String, pubkey: String) {
        if (process != null) return 

        // Mengambil lokasi libdnstt.so yang sudah diekstrak Android
        val binaryPath = File(context.applicationInfo.nativeLibraryDir, "libdnstt.so").absolutePath
        val cleanDomain = domain.trim()

        // Perintah eksekusi
        val command = listOf(
            binaryPath,
            "-doh", "https://cloudflare-dns.com/dns-query", 
            "-pubkey-file", pubkey,
            cleanDomain,
            "127.0.0.1:$SLOW_DNS_LOCAL_PORT"
        )

        try {
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)
            process = pb.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}
