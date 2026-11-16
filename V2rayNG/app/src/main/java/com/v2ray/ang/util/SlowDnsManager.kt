package com.v2ray.ang.util

import android.content.Context
import java.io.File

object SlowDnsManager {
    private var process: Process? = null
    const val SLOW_DNS_LOCAL_PORT = 10808

    fun start(context: Context, domain: String, pubkey: String, doh: String) {
        if (process != null) return 

        val binaryPath = File(context.applicationInfo.nativeLibraryDir, "libdnstt.so").absolutePath
        
        // Kita simpan pubkey ke file sementara di memori HP
        val pubkeyFile = File(context.filesDir, "dnstt_pubkey.txt")
        try {
            pubkeyFile.writeText(pubkey.trim())
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        // Susun perintah dnstt-client dengan DoH dinamis
        val command = listOf(
            binaryPath,
            "-doh", doh.ifEmpty { "https://cloudflare-dns.com/dns-query" },
            "-pubkey-file", pubkeyFile.absolutePath,
            domain.trim(),
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
