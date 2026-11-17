package com.v2ray.ang.util

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Cek langsung ke server (tanpa lewat core) tiap kali connect, untuk VMess/VLESS/Trojan:
 * DNS server, TCP, TLS, dan permintaan WebSocket. Hasil masuk ke tab LOG supaya terlihat
 * apakah masalahnya di server/profil atau di aplikasi. Tidak mengirim kredensial apa pun.
 */
object ServerProbe {
    fun run(p: ProfileItem) {
        if (p.configType != EConfigType.VMESS && p.configType != EConfigType.VLESS && p.configType != EConfigType.TROJAN) return
        if (p.slowDnsEnabled) return
        thread(isDaemon = true, name = "server-probe") { probe(p) }
    }

    private fun trustAll(): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
    }

    private fun probe(p: ProfileItem) {
        val host = p.server.orEmpty().trim()
        val port = p.serverPort.orEmpty().trim().toIntOrNull() ?: return
        val tag = "Cek server $host:$port"
        var socket: Socket? = null
        try {
            val t0 = System.nanoTime()
            val ip = InetAddress.getByName(host)
            TunnelLog.add("$tag: DNS -> ${ip.hostAddress} (${(System.nanoTime() - t0) / 1_000_000} ms)")

            val t1 = System.nanoTime()
            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(ip, port), 6000)
            s.soTimeout = 8000
            TunnelLog.add("$tag: TCP tersambung (${(System.nanoTime() - t1) / 1_000_000} ms)")

            val tls = !p.security.isNullOrEmpty() && !p.security.equals("none", true)
            val sniHost = p.sni?.takeIf { it.isNotBlank() } ?: p.host?.takeIf { it.isNotBlank() } ?: host
            var conn: Socket = s
            if (tls) {
                val t2 = System.nanoTime()
                val ssl = trustAll().socketFactory.createSocket(s, sniHost, port, true) as SSLSocket
                ssl.soTimeout = 8000
                ssl.startHandshake()
                conn = ssl
                TunnelLog.add("$tag: TLS OK ${ssl.session.protocol} SNI=$sniHost (${(System.nanoTime() - t2) / 1_000_000} ms)")
            }

            if (p.network.equals("ws", true)) {
                val path = p.path?.takeIf { it.isNotBlank() } ?: "/"
                val hostHeader = p.host?.takeIf { it.isNotBlank() } ?: sniHost
                val req = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
                conn.getOutputStream().write(req.toByteArray())
                conn.getOutputStream().flush()
                val line = conn.getInputStream().bufferedReader().readLine().orEmpty()
                val code = line.split(" ").getOrNull(1) ?: "?"
                val verdict = when (code) {
                    "101" -> "jalur WS ada dan dijawab (normal)"
                    "400", "426" -> "server menjawab, jalur WS ada (normal)"
                    "404" -> "PATH WS tidak ditemukan di server, cek path profil dan routing proxy server"
                    "502", "503", "504" -> "server depan (nginx/haproxy) hidup tapi backend Xray tidak menjawab"
                    else -> "balasan tidak biasa"
                }
                TunnelLog.add("$tag: WS $path -> HTTP $code, $verdict")
            } else {
                TunnelLog.add("$tag: transport ${p.network ?: "tcp"}, tes WS dilewati")
            }
        } catch (e: Exception) {
            TunnelLog.add("$tag: GAGAL (${e.javaClass.simpleName}: ${e.message})")
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
        coreTest()
    }

    /**
     * Tes lewat core (SOCKS lokal Xray): satu ke alamat IP, satu ke nama domain.
     * IP ok + domain gagal = server tidak bisa resolve DNS; keduanya gagal = core/VMess tidak tembus;
     * keduanya ok = core normal dan masalahnya di jalur tun/DNS HP.
     */
    private fun coreTest() {
        try {
            Thread.sleep(3000)
            TunnelLog.add("Tes lewat core: ke IP 1.1.1.1 ...")
            AutoPing.testOnce("http://1.1.1.1/")
            TunnelLog.add("Tes lewat core: ke domain www.gstatic.com ...")
            AutoPing.testOnce("http://www.gstatic.com/generate_204")
        } catch (_: Exception) {
        }
    }
}
