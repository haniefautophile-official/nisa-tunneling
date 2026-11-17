package com.v2ray.ang.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.v2ray.ang.extension.toast
import com.v2ray.ang.dto.entities.ProfileItem
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Menjalankan helper libsshsocks.so (klien SSH + SOCKS5 lokal, setara "ssh -D").
 * Core Xray hanya meneruskan lalu lintas ke 127.0.0.1:SSH_LOCAL_PORT.
 * Jika SlowDNS aktif, SSH disambungkan lewat 127.0.0.1:SlowDnsManager.SLOW_DNS_LOCAL_PORT.
 */
object SshManager {
    private const val TAG = "GoLog" // tag yang ditampilkan menu Logcat aplikasi
    private var process: Process? = null
    @Volatile private var stopping = false

    /** Logcat di sebagian HP tidak menampilkan log, jadi status juga ditampilkan lewat toast. */
    private fun say(context: Context, msg: String) {
        Log.i(TAG, msg)
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try { app.toast(msg.take(300), true) } catch (_: Exception) { }
        }
    }

    // Port SOCKS bawaan aplikasi 10808 dan SlowDNS 10899, jadi pakai 10898.
    const val SSH_LOCAL_PORT = 10898

    // Process.isAlive butuh API 26, sedangkan minSdk aplikasi 24.
    private fun alive(p: Process): Boolean =
        try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }

    /**
     * Binary Go statis di Android tidak punya /etc/resolv.conf, jadi tidak bisa resolve domain.
     * Domain di-resolve di sini (DNS Android) lalu IP-nya diberikan ke helper.
     */
    private fun resolveIp(host: String): String {
        if (host.contains(":") || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return host
        val all = InetAddress.getAllByName(host)
        return (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress.orEmpty()
    }

    private fun hostPort(host: String, port: String) =
        if (host.contains(":")) "[$host]:$port" else "$host:$port"

    @Synchronized
    fun start(context: Context, profile: ProfileItem) {
        say(context, "SSH: memulai helper...")
        process?.let { if (alive(it)) return }
        process = null
        stopping = false

        val host = profile.server.orEmpty().trim()
        val port = profile.serverPort.orEmpty().trim()
        val user = profile.username.orEmpty().trim()
        val pass = profile.password.orEmpty()
        if (host.isEmpty() || port.isEmpty() || user.isEmpty()) {
            say(context, "SSH: host, port, atau username kosong")
            return
        }

        val binary = File(context.applicationInfo.nativeLibraryDir, "libsshsocks.so")
        if (!binary.exists()) {
            say(context, "SSH: libsshsocks.so tidak ada di APK (cek folder jniLibs)")
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
        try {
            if (profile.slowDnsEnabled) {
                cmd += listOf("-connect", "127.0.0.1:${SlowDnsManager.SLOW_DNS_LOCAL_PORT}")
            } else if (!profile.sshProxy.isNullOrBlank()) {
                // Format [user:pass@]host:port, host proxy di-resolve ke IP.
                val raw = profile.sshProxy.orEmpty().trim()
                val at = raw.lastIndexOf('@')
                val auth = if (at >= 0) raw.substring(0, at + 1) else ""
                val hp = raw.substring(at + 1)
                val c = hp.lastIndexOf(':')
                val pHost = if (c > 0) hp.substring(0, c) else hp
                val pPort = if (c > 0) hp.substring(c + 1) else "80"
                cmd += listOf("-proxy", auth + hostPort(resolveIp(pHost), pPort))
            } else {
                val ip = resolveIp(host)
                say(context, "SSH: $host -> $ip")
                cmd += listOf("-connect", hostPort(ip, port))
            }
        } catch (e: Exception) {
            say(context, "SSH: gagal resolve domain $host: ${e.message}")
            return
        }
        if (profile.sshTls) {
            cmd += "-tls"
            if (!profile.sshSni.isNullOrBlank()) cmd += listOf("-sni", profile.sshSni.orEmpty().trim())
        }
        val payload = profile.sshPayload.orEmpty().trim()
        if (payload.isNotEmpty()) {
            try {
                // Baris baru sungguhan dianggap [crlf]; placeholder lain dibaca oleh helper.
                val normalized = payload.replace("\r\n", "\n").replace("\n", "[crlf]")
                val payloadFile = File(context.filesDir, "ssh_payload.txt")
                payloadFile.writeText(normalized)
                cmd += listOf("-payload-file", payloadFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "SSH: gagal menulis payload", e)
                return
            }
        }

        say(
            context,
            "SSH cfg: $hostPort tls=${profile.sshTls} sni=${profile.sshSni.orEmpty()} " +
                "payload=${payload.length}c proxy=${!profile.sshProxy.isNullOrBlank()} slowdns=${profile.slowDnsEnabled}"
        )
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["SSH_PASS"] = pass // lewat environment, bukan argumen proses
            val p = pb.start()
            process = p
            thread(isDaemon = true, name = "sshsocks-log") {
                val tail = ArrayDeque<String>()
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        Log.i(TAG, line)
                        tail.addLast(line)
                        if (tail.size > 4) tail.removeFirst()
                        if (!stopping) say(context, "SSH: $line")
                    }
                } catch (_: Exception) {
                }
                Log.w(TAG, "sshsocks berhenti")
                if (!stopping) {
                    val code = try { p.waitFor() } catch (_: Exception) { -1 }
                    say(context, "SSH berhenti (kode $code): " + tail.joinToString(" | "))
                }
            }
        } catch (e: Exception) {
            say(context, "SSH: gagal menjalankan helper: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        stopping = true
        process?.destroy()
        process = null
    }
}
