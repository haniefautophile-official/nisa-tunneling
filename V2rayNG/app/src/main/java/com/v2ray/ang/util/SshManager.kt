package com.v2ray.ang.util

import android.content.Context
import android.util.Log
import com.v2ray.ang.dto.entities.ProfileItem
import java.io.File
import kotlin.concurrent.thread

/**
 * Menjalankan helper libsshsocks.so (klien SSH + SOCKS5 lokal, setara "ssh -D").
 * Core Xray hanya meneruskan lalu lintas ke 127.0.0.1:SSH_LOCAL_PORT.
 * Jika SlowDNS aktif, SSH disambungkan lewat 127.0.0.1:SlowDnsManager.SLOW_DNS_LOCAL_PORT.
 */
object SshManager {
    private const val TAG = "GoLog" // tag yang ditampilkan menu Logcat aplikasi
    private var process: Process? = null

    // Port SOCKS bawaan aplikasi 10808 dan SlowDNS 10899, jadi pakai 10898.
    const val SSH_LOCAL_PORT = 10898

    // Process.isAlive butuh API 26, sedangkan minSdk aplikasi 24.
    private fun alive(p: Process): Boolean =
        try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }

    @Synchronized
    fun start(context: Context, profile: ProfileItem) {
        process?.let { if (alive(it)) return }
        process = null

        val host = profile.server.orEmpty().trim()
        val port = profile.serverPort.orEmpty().trim()
        val user = profile.username.orEmpty().trim()
        val pass = profile.password.orEmpty()
        if (host.isEmpty() || port.isEmpty() || user.isEmpty()) {
            Log.e(TAG, "SSH: host, port, atau username kosong")
            return
        }

        val binary = File(context.applicationInfo.nativeLibraryDir, "libsshsocks.so")
        if (!binary.exists()) {
            Log.e(TAG, "SSH: libsshsocks.so tidak ada di APK (cek folder jniLibs)")
            return
        }

        // Sidik jari host disimpan saat pertama kali, dan sambungan ditolak jika berubah.
        val fpName = "ssh_hostkey_" + "$host:$port".replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt"
        val knownHosts = File(context.filesDir, fpName)

        val hostPort = if (host.contains(":")) "[$host]:$port" else "$host:$port"
        val cmd = mutableListOf(
            binary.absolutePath,
            "-host", hostPort,
            "-user", user,
            "-listen", "127.0.0.1:$SSH_LOCAL_PORT",
            "-known-hosts", knownHosts.absolutePath
        )
        if (profile.slowDnsEnabled) {
            cmd += listOf("-connect", "127.0.0.1:${SlowDnsManager.SLOW_DNS_LOCAL_PORT}")
        }

        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["SSH_PASS"] = pass // lewat environment, bukan argumen proses
            val p = pb.start()
            process = p
            thread(isDaemon = true, name = "sshsocks-log") {
                try {
                    p.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) }
                } catch (_: Exception) {
                }
                Log.w(TAG, "sshsocks berhenti")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menjalankan sshsocks", e)
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
    }
}
