package com.v2ray.ang.util

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Probe tipe record DNS untuk Type "Otomatis" SlowDNS.
 *
 * Mengirim satu query UDP (dengan EDNS0) per tipe record ke resolver yang dipakai profil,
 * semuanya paralel, lalu mengurutkan tipe mana yang paling layak dicoba:
 *  - SERVED   : balasan NOERROR dan ada record bertipe itu (kemungkinan besar dijawab server tunnel)
 *  - ANSWERED : ada balasan lain (NXDOMAIN, dll; bisa jawaban asli server, bisa balasan palsu resolver)
 *  - SILENT   : tidak ada balasan sama sekali (resolver menjatuhkan tipe itu) -> tidak dicoba
 *
 * Ini hanya penyaring awal. Penentu akhirnya tetap jabat tangan klien dnstt (lihat SlowDnsManager).
 */
object SlowDnsTypeProbe {
    enum class Verdict { SERVED, ANSWERED, SILENT }

    class Result(val type: String, val verdict: Verdict, val rcode: Int, val ms: Long) {
        override fun toString(): String =
            if (verdict == Verdict.SILENT) "$type=diam" else "$type=${verdict.name.lowercase()}(rcode $rcode, ${ms}ms)"
    }

    private val CODES = linkedMapOf(
        "txt" to 16, "null" to 10, "cname" to 5, "mx" to 15, "ns" to 2,
        "srv" to 33, "a" to 1, "aaaa" to 28, "caa" to 257,
    )

    /** Tipe yang dipakai server NISA SlowDNS: selalu dicoba paling dulu (kalau resolver tidak menjatuhkannya). */
    const val PREFERRED_TYPE = "aaaa"

    /** Urutan prioritas bawaan: AAAA dulu, sisanya mengikuti urutan di CODES. */
    val ALL_TYPES: List<String> = listOf(PREFERRED_TYPE) + CODES.keys.filter { it != PREFERRED_TYPE }

    private const val B32 = "abcdefghijklmnopqrstuvwxyz234567"
    private val rnd = SecureRandom()

    private fun randomLabel(len: Int = 40): String = buildString { repeat(len) { append(B32[rnd.nextInt(32)]) } }

    fun buildQuery(id: Int, name: String, qtype: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun w16(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        w16(id); w16(0x0100); w16(1); w16(0); w16(0); w16(1) // 1 pertanyaan, 1 record tambahan (OPT)
        for (label in name.trim('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size)
            out.write(b)
        }
        out.write(0); w16(qtype); w16(1)
        // OPT RR: nama root, type 41, ukuran UDP 4096, ttl 0, rdlen 0
        out.write(0); w16(41); w16(4096); w16(0); w16(0); w16(0)
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    private fun skipName(buf: ByteArray, len: Int, pos: Int): Int {
        var p = pos
        while (true) {
            if (p >= len) return -1
            val l = buf[p].toInt() and 0xFF
            if (l == 0) return p + 1
            if ((l and 0xC0) == 0xC0) return if (p + 2 <= len) p + 2 else -1
            p += l + 1
        }
    }

    /** Pair(rcode, adaRecordBertipeQtype) atau null kalau bukan balasan untuk query ini. */
    fun parseResponse(buf: ByteArray, len: Int, id: Int, qtype: Int): Pair<Int, Boolean>? {
        if (len < 12) return null
        if (u16(buf, 0) != id) return null
        val flags = u16(buf, 2)
        if ((flags and 0x8000) == 0) return null
        val rcode = flags and 0x0F
        val qd = u16(buf, 4)
        val an = u16(buf, 6)
        var pos = 12
        for (i in 0 until qd) {
            pos = skipName(buf, len, pos)
            if (pos < 0) return Pair(rcode, false)
            pos += 4
        }
        var found = false
        for (i in 0 until an) {
            pos = skipName(buf, len, pos)
            if (pos < 0 || pos + 10 > len) break
            if (u16(buf, pos) == qtype) found = true
            pos += 10 + u16(buf, pos + 8)
        }
        return Pair(rcode, found)
    }

    private fun probeOne(addr: InetSocketAddress, domain: String, type: String, timeoutMs: Int): Result {
        val qtype = CODES[type] ?: return Result(type, Verdict.SILENT, -1, 0)
        val id = rnd.nextInt(65536)
        val query = buildQuery(id, randomLabel() + "." + domain.trim().trimEnd('.'), qtype)
        val t0 = System.nanoTime()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                s.send(DatagramPacket(query, query.size, addr))
                val buf = ByteArray(4096)
                val pkt = DatagramPacket(buf, buf.size)
                while (true) {
                    s.receive(pkt) // melempar SocketTimeoutException kalau diam
                    val r = parseResponse(buf, pkt.length, id, qtype) ?: continue
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val v = if (r.first == 0 && r.second) Verdict.SERVED else Verdict.ANSWERED
                    return Result(type, v, r.first, ms)
                }
            }
        } catch (_: Exception) {
        }
        return Result(type, Verdict.SILENT, -1, 0)
    }

    /** Jalankan di thread latar (bukan thread utama). Semua tipe dicoba paralel. */
    fun probeAll(
        address: String,
        domain: String,
        types: List<String> = ALL_TYPES,
        timeoutMs: Int = 2000,
    ): List<Result> {
        val hostPort = if (address.contains(':')) address else "$address:53"
        val idx = hostPort.lastIndexOf(':')
        val host = hostPort.substring(0, idx)
        val port = hostPort.substring(idx + 1).toIntOrNull() ?: 53
        val addr = try {
            InetSocketAddress(InetAddress.getByName(host), port)
        } catch (_: Exception) {
            return types.map { Result(it, Verdict.SILENT, -1, 0) }
        }
        val results = arrayOfNulls<Result>(types.size)
        val threads = types.mapIndexed { i, t ->
            thread(isDaemon = true, name = "slowdns-probe-$t") { results[i] = probeOne(addr, domain, t, timeoutMs) }
        }
        threads.forEach { it.join(timeoutMs + 1500L) }
        return types.mapIndexed { i, t -> results[i] ?: Result(t, Verdict.SILENT, -1, 0) }
    }

    /**
     * Urutan tipe yang dicoba: [PREFERRED_TYPE] (AAAA) paling depan selama resolver tidak menjatuhkannya,
     * lalu SERVED, lalu ANSWERED, tiap kelompok mengikuti [ALL_TYPES]. SILENT dibuang.
     * [learned] (tipe yang pernah berhasil di jaringan ini) dinaikkan ke depan, tetapi hanya kalau bukan AAAA
     * yang masih lolos: AAAA tetap yang pertama.
     */
    fun rank(results: List<Result>, learned: String? = null): List<String> {
        val names = results.filter { it.verdict != Verdict.SILENT }
            .sortedWith(compareBy({ if (it.verdict == Verdict.SERVED) 0 else 1 }, { ALL_TYPES.indexOf(it.type) }))
            .map { it.type }
        val front = when {
            PREFERRED_TYPE in names -> PREFERRED_TYPE
            learned != null && learned in names -> learned
            else -> null
        }
        return if (front != null) listOf(front) + names.filter { it != front } else names
    }
}
