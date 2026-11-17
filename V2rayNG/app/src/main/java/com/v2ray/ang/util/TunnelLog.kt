package com.v2ray.ang.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log tunnel (SSH / SlowDNS / ping) untuk tab LOG di layar utama.
 * Service VPN berjalan di proses ":daemon" sedangkan UI di proses utama, jadi log disimpan
 * di berkas (filesDir/tunnel.log) yang dibaca UI secara berkala, bukan di memori.
 */
object TunnelLog {
    private const val MAX_LINES = 300
    private const val MAX_BYTES = 64 * 1024

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, "tunnel.log")
    }

    @Synchronized
    fun add(message: String) {
        Log.i("NisaLog", message)
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val tail = readLines().takeLast(100)
                f.writeText(tail.joinToString("\n") + "\n")
            }
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            f.appendText("[$time] ${message.replace('\n', ' ')}\n")
        } catch (_: Exception) {
        }
    }

    fun readLines(): List<String> = try {
        val f = file
        if (f != null && f.exists()) f.readLines().takeLast(MAX_LINES) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun clear() {
        try {
            file?.writeText("")
        } catch (_: Exception) {
        }
    }
}
