package com.v2ray.ang.util

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Menjalankan helper libsshsocks.so (klien SSH + SOCKS5 lokal, setara "ssh -D") dan menjaganya:
 * kalau helper mati sendiri, dicoba lagi sampai MAX_RETRY kali. Semua status masuk ke TunnelLog.
 * Jika SlowDNS aktif, SSH disambungkan lewat 127.0.0.1:SlowDnsManager.SLOW_DNS_LOCAL_PORT.
 */
object SshManager {
    // Port SOCKS bawaan aplikasi 10808 dan SlowDNS 10899, jadi pakai 10898.
    const val SSH_LOCAL_PORT = 10898

    private const val MAX_RETRY = 5
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val RETRY_DELAY_MS = 3000L
    private const val STABLE_MS = 20_000L

    private var process: Process? = null
    @Volatile private var stopping = false
    @Volatile private var generation = 0

    private class Launched(val process: Process?, val fatal: Boolean)

    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Semua IP hasil DNS (IPv4 dulu, maksimal 6). Binary Go di Android tidak punya DNS sendiri. */
    private fun resolveAll(host: String): List<String> {
        if (host.contains(":") || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return listOf(host)
        return try {
            val all = InetAddress.getAllByName(host).toList()
            val ordered = all.filter { it is Inet4Address } + all.filter { it !is Inet4Address }
            val ips = ordered.mapNotNull { it.hostAddress }.take(6)
            if (ips.isNotEmpty()) dnsCache[host] = ips
            ips
        } catch (e: Exception) {
            // DNS HP kadang gagal sesaat (sering saat retry): pakai hasil resolve sebelumnya
            dnsCache[host]?.also { TunnelLog.detail("SSH: DNS $host gagal, pakai cache") } ?: throw e
        }
    }

    /** Banner server kadang berisi HTML (<font>, <br>): buang tag supaya tampil bersih di tab LOG. */
    private fun stripHtml(s: String): String = s
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .trim()

    private fun hostPort(host: String, port: String) =
        if (host.contains(":")) "[$host]:$port" else "$host:$port"

    private fun hostPortList(ips: List<String>, port: String) =
        ips.joinToString(",") { hostPort(it, port) }

    private fun active(gen: Int) = !stopping && gen == generation

    fun start(context: Context, profile: ProfileItem) {
        val app = context.applicationContext
        TunnelLog.init(app)
        TunnelLog.detail("SSH: sesi baru")
        var gen = 0
        synchronized(this) {
            stopLocked()
            stopping = false
            generation++
            gen = generation
        }
        thread(isDaemon = true, name = "ssh-supervisor") { supervise(app, profile, gen) }
    }

    fun stop() {
        synchronized(this) { stopLocked() }
    }

    private fun stopLocked() {
        stopping = true
        generation++
        process?.let {
            try { it.destroy() } catch (_: Exception) { }
        }
        process = null
    }

    private fun supervise(ctx: Context, profile: ProfileItem, gen: Int) {
        var attempt = 0
        while (active(gen)) {
            try { File(ctx.filesDir, "ssh_banner.txt").delete() } catch (_: Exception) { }
            val launched = launch(ctx, profile, gen)
            if (launched.fatal) return
            val p = launched.process
            var connectedAt = 0L
            var code = -1
            if (p != null) {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (active(gen)) {
                            // Banner server SSH disimpan untuk kotak log di layar utama
                            val rawLine = line.removePrefix("[sshsocks] ")
                            if (rawLine.startsWith("banner: ")) {
                                try { File(ctx.filesDir, "ssh_banner.txt").appendText(stripHtml(rawLine.removePrefix("banner: ")).let { if (it.isEmpty()) "" else it + "\n" }) } catch (_: Exception) { }
                            }
                            if (line.contains("SOCKS5 siap")) {
                                connectedAt = System.currentTimeMillis()
                                TunnelLog.add("SSH login berhasil, menguji internet...")
                                AutoPing.verify()
                                TunnelLog.detail("SSH: " + line.removePrefix("[sshsocks] "))
                            } else if (!line.contains("forward ") && !rawLine.startsWith("banner: ")) {
                                TunnelLog.essential("SSH: " + line.removePrefix("[sshsocks] "))
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                code = try { p.waitFor() } catch (_: Exception) { -1 }
            }
            if (!active(gen)) return
            if (connectedAt > 0 && System.currentTimeMillis() - connectedAt >= STABLE_MS) attempt = 0
            attempt++
            if (attempt > MAX_RETRY) {
                TunnelLog.add("SSH: gagal setelah $MAX_RETRY percobaan, berhenti")
                return
            }
            TunnelLog.add("SSH terputus, mencoba lagi ($attempt/$MAX_RETRY)")
            TunnelLog.detail("SSH: kode keluar $code, jeda 3s")
            try { Thread.sleep(RETRY_DELAY_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun launch(context: Context, profile: ProfileItem, gen: Int): Launched {
        val host = profile.server.orEmpty().trim()
        val port = profile.serverPort.orEmpty().trim()
        val user = profile.username.orEmpty().trim()
        val pass = profile.password.orEmpty()
        if (host.isEmpty() || port.isEmpty() || user.isEmpty()) {
            TunnelLog.add("SSH: host, port, atau username kosong")
            return Launched(null, true)
        }

        val binary = File(context.applicationInfo.nativeLibraryDir, "libsshsocks.so")
        if (!binary.exists()) {
            TunnelLog.add("SSH: libsshsocks.so tidak ada di APK (cek folder jniLibs)")
            return Launched(null, true)
        }

        // Sidik jari host disimpan saat pertama kali, dan sambungan ditolak jika berubah.
        val fpName = "ssh_hostkey_" + "$host:$port".replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt"
        val knownHosts = File(context.filesDir, fpName)

        val target = hostPort(host, port)
        val cmd = mutableListOf(
            binary.absolutePath,
            "-host", target,
            "-user", user,
            "-listen", "127.0.0.1:$SSH_LOCAL_PORT",
            "-known-hosts", knownHosts.absolutePath
        )

        try {
            if (profile.slowDnsEnabled) {
                cmd += listOf("-connect", "127.0.0.1:${SlowDnsManager.SLOW_DNS_LOCAL_PORT}")
            } else if (!profile.sshProxy.isNullOrBlank()) {
                // Format [user:pass@]host:port. Semua IP host proxy dicoba berurutan oleh helper.
                val raw = profile.sshProxy.orEmpty().trim()
                val hp = raw.substring(raw.lastIndexOf('@') + 1)
                val c = hp.lastIndexOf(':')
                val pHost = if (c > 0) hp.substring(0, c) else hp
                val pPort = if (c > 0) hp.substring(c + 1) else "80"
                val pIps = resolveAll(pHost)
                TunnelLog.detail("SSH: proxy $pHost -> ${pIps.joinToString()}")
                cmd += listOf("-proxy", raw)
                cmd += listOf("-connect", hostPortList(pIps, pPort))
            } else {
                val ips = resolveAll(host)
                TunnelLog.detail("SSH: $host -> ${ips.joinToString()}")
                cmd += listOf("-connect", hostPortList(ips, port))
            }
        } catch (e: Exception) {
            TunnelLog.add("SSH: gagal resolve domain: ${e.message}")
            return Launched(null, false)
        }

        if (profile.sshTls) {
            cmd += "-tls"
            if (!profile.sshSni.isNullOrBlank()) cmd += listOf("-sni", profile.sshSni.orEmpty().trim())
        }
        val payload = profile.sshPayload.orEmpty().trim()
        if (payload.isNotEmpty()) {
            try {
                // Baris baru sungguhan dianggap [crlf]; placeholder lain dibaca oleh helper.
                // [ua] dan [method] dibaca di sini; placeholder lain diproses helper.
                val normalized = payload.replace("\r\n", "\n").replace("\n", "[crlf]")
                    .replace("[ua]", USER_AGENT, ignoreCase = true)
                    .replace("[method]", "CONNECT", ignoreCase = true)
                val payloadFile = File(context.filesDir, "ssh_payload.txt")
                payloadFile.writeText(normalized)
                cmd += listOf("-payload-file", payloadFile.absolutePath)
            } catch (e: Exception) {
                TunnelLog.add("SSH: gagal menulis payload: ${e.message}")
                return Launched(null, true)
            }
        }

        TunnelLog.detail(
            "SSH cfg: $target tls=${profile.sshTls} sni=${profile.sshSni.orEmpty()} " +
                "payload=${payload.length}c proxy=${!profile.sshProxy.isNullOrBlank()} slowdns=${profile.slowDnsEnabled}"
        )
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["SSH_PASS"] = pass // lewat environment, bukan argumen proses
            val p = pb.start()
            val accepted = synchronized(this) {
                if (active(gen)) {
                    process = p
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                p.destroy()
                Launched(null, true)
            } else {
                Launched(p, false)
            }
        } catch (e: Exception) {
            TunnelLog.add("SSH: gagal menjalankan helper: ${e.message}")
            Launched(null, false)
        }
    }
}
