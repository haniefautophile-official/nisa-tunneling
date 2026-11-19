package com.v2ray.ang.util

import android.content.Context
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Auto ping global (semua protokol): selama core jalan, ping berkala lewat SOCKS5 lokal Xray,
 * jadi yang diukur adalah protokol/profil yang sedang aktif. Hasil masuk ke tab LOG.
 * Juga menyalin peringatan/error dari log core (Xray) ke tab LOG.
 * Pengaturan: Pengaturan > Advanced Settings > Auto ping.
 */
object AutoPing {
    const val KEY_ENABLED = "pref_nisa_ping_enabled"
    const val KEY_URL = "pref_nisa_ping_url"
    const val KEY_INTERVAL = "pref_nisa_ping_interval"
    // Bawaan seperti HTTP Custom: www.google.com, balasan 204. Tujuan lain (mis. dns.google) membalas 200.
    private const val DEFAULT_URL = "http://www.google.com/generate_204"
    private const val DEFAULT_INTERVAL = 30

    @Volatile
    private var generation = 0
    private var logcatProcess: Process? = null

    @Synchronized
    fun start(context: Context, hideCoreLog: Boolean = false) {
        TunnelLog.init(context)
        generation++
        val gen = generation
        // Akun terkunci: log core bisa memuat alamat server, jadi tidak disalin ke tab LOG
        if (hideCoreLog) {
            try { logcatProcess?.destroy() } catch (_: Exception) { }
            logcatProcess = null
        } else {
            startMirror()
        }
        thread(isDaemon = true, name = "auto-ping") { loop(gen) }
    }

    @Synchronized
    fun stop() {
        generation++
        try { logcatProcess?.destroy() } catch (_: Exception) { }
        logcatProcess = null
    }

    /** Satu kali ping lewat SOCKS lokal Xray (dipakai ServerProbe), hasil masuk ke tab LOG. */
    fun testOnce(url: String): Boolean = ping(url)

    /** URL tujuan ping yang dipilih pengguna (kosong = bawaan). */
    fun currentUrl(): String =
        MmkvManager.decodeSettingsString(KEY_URL, DEFAULT_URL)?.trim().orEmpty().ifEmpty { DEFAULT_URL }

    private fun currentInterval(): Int =
        (MmkvManager.decodeSettingsString(KEY_INTERVAL, "")?.trim()?.toIntOrNull() ?: DEFAULT_INTERVAL).coerceIn(5, 3600)

    private fun hostOf(url: String): String = try { URL(url).host } catch (_: Exception) { url }

    private fun shortReason(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "timeout"
        is javax.net.ssl.SSLException -> "TLS gagal" + (e.message?.let { ": " + it.take(60) } ?: "")
        else -> (e.message ?: e.javaClass.simpleName).take(80)
    }

    private fun pause(ms: Long): Boolean =
        try { Thread.sleep(ms); true } catch (_: InterruptedException) { false }

    private fun loop(gen: Int) {
        var fails = 0
        var wasEnabled = false
        var lastUrl = ""
        var lastInterval = -1
        if (!pause(2000)) return
        while (gen == generation) {
            if (!MmkvManager.decodeSettingsBool(KEY_ENABLED, false)) {
                wasEnabled = false
                if (!pause(3000)) return
                continue
            }
            val url = currentUrl()
            val interval = currentInterval()
            if (!wasEnabled || url != lastUrl || interval != lastInterval) {
                TunnelLog.add("Ping ke ${hostOf(url)} tiap ${interval}s")
                wasEnabled = true
                lastUrl = url
                lastInterval = interval
                fails = 0
            }
            if (ping(url)) fails = 0 else fails++
            if (fails == 3) TunnelLog.add("Ping gagal 3 kali berturut-turut, koneksi mungkin terputus")
            // Tunggu per detik supaya perubahan tujuan/interval di pengaturan langsung berlaku
            var waited = 0
            while (waited < interval && gen == generation) {
                if (!pause(1000)) return
                waited++
                if (!MmkvManager.decodeSettingsBool(KEY_ENABLED, false) || currentUrl() != url) break
            }
        }
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw java.io.EOFException("koneksi ditutup")
            off += r
        }
        return buf
    }

    /** GET lewat SOCKS5 lokal Xray (mendukung tanpa auth dan user/pass). */
    private fun ping(urlStr: String): Boolean {
        var socks: Socket? = null
        return try {
            val u = URL(urlStr)
            val host = u.host
            val https = u.protocol.equals("https", ignoreCase = true)
            val port = if (u.port > 0) u.port else if (https) 443 else 80
            val path = if (u.file.isNullOrEmpty()) "/" else u.file

            val t0 = System.nanoTime()
            val s = Socket()
            socks = s
            s.connect(InetSocketAddress("127.0.0.1", SettingsManager.getSocksPort()), 5000)
            s.soTimeout = 10000
            val input = s.getInputStream()
            val output = s.getOutputStream()

            val user = SettingsManager.getSocksUsername()
            val pass = SettingsManager.getSocksPassword()
            val useAuth = user != null && pass != null
            output.write(if (useAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
            output.flush()
            val greeting = readN(input, 2)
            if (greeting[0].toInt() != 5) error("balasan SOCKS tidak valid")
            when (greeting[1].toInt()) {
                0 -> {}
                2 -> {
                    if (!useAuth) error("SOCKS minta autentikasi")
                    val ub = user!!.toByteArray()
                    val pb = pass!!.toByteArray()
                    output.write(byteArrayOf(1, ub.size.toByte()) + ub + byteArrayOf(pb.size.toByte()) + pb)
                    output.flush()
                    if (readN(input, 2)[1].toInt() != 0) error("autentikasi SOCKS ditolak")
                }
                else -> error("metode SOCKS tidak didukung")
            }

            val hb = host.toByteArray()
            output.write(
                byteArrayOf(5, 1, 0, 3, hb.size.toByte()) + hb +
                    byteArrayOf((port shr 8).toByte(), port.toByte())
            )
            output.flush()
            val head = readN(input, 4)
            if (head[1].toInt() != 0) error("CONNECT ditolak (kode ${head[1].toInt()})")
            val skip = when (head[3].toInt()) {
                1 -> 6
                4 -> 18
                3 -> (readN(input, 1)[0].toInt() and 0xFF) + 2
                else -> 6
            }
            readN(input, skip)

            var conn: Socket = s
            if (https) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(s, host, port, true) as SSLSocket
                ssl.soTimeout = 10000
                ssl.startHandshake()
                conn = ssl
            }
            conn.getOutputStream().write(
                "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: NISA-ping\r\nConnection: close\r\n\r\n".toByteArray()
            )
            conn.getOutputStream().flush()
            val status = conn.getInputStream().bufferedReader().readLine().orEmpty()
            val ms = (System.nanoTime() - t0) / 1_000_000
            val code = status.split(" ").getOrNull(1) ?: "?"
            val ok = (code.toIntOrNull() ?: 0) in 200..399
            // Format seperti HTTP Custom: "Ping 204 OK (55ms)"
            TunnelLog.add(if (ok) "Ping $code OK (${ms}ms)" else "Ping $code (${ms}ms)")
            ok
        } catch (e: Exception) {
            TunnelLog.add("Ping gagal (${shortReason(e)})")
            false
        } finally {
            try { socks?.close() } catch (_: Exception) { }
        }
    }

    /** Menyalin baris [Warning]/[Error] dari log core (tag GoLog) ke tab LOG. */
    private fun startMirror() {
        try { logcatProcess?.destroy() } catch (_: Exception) { }
        try {
            val p = ProcessBuilder("logcat", "-v", "brief", "-T", "1", "GoLog:I", "*:S")
                .redirectErrorStream(true).start()
            logcatProcess = p
            thread(isDaemon = true, name = "core-log-mirror") {
                var last = ""
                var lastAt = 0L
                var lastDnsAt = 0L
                try {
                    p.inputStream.bufferedReader().forEachLine { raw ->
                        val w = raw.indexOf("[Warning]")
                        val e = raw.indexOf("[Error]")
                        val i = if (w >= 0) w else e
                        if (i >= 0) {
                            val msg = raw.substring(i).take(220)
                            val now = System.currentTimeMillis()
                            // Kegagalan DNS lewat tunnel muncul puluhan kali per detik: ringkas jadi satu baris per menit
                            if (!TunnelLog.isDetail() && msg.contains("app/dns")) {
                                if (now - lastDnsAt > 60_000) {
                                    lastDnsAt = now
                                    TunnelLog.add("DNS: gagal lewat tunnel (aktifkan Log detail untuk rincian)")
                                }
                                return@forEachLine
                            }
                            if (msg != last || now - lastAt > 5000) {
                                last = msg
                                lastAt = now
                                TunnelLog.add("Core $msg")
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            TunnelLog.add("Core: gagal membaca log core: ${e.message}")
        }
    }
}
