package com.v2ray.ang.util

/**
 * Opsi tambahan klien SlowDNS untuk mode UDP. Ditulis setelah alamat resolver di kolom DNS,
 * dipisah '|', antar-opsi dipisah ',':
 *
 *   112.215.198.254|rps=40,workers=20,timeout=1500ms
 *   112.215.198.254:53|shared=1,rps=40
 *
 * Kunci: rps (batas query/detik), workers (jumlah worker UDP), timeout (tunggu balasan per query),
 * shared (1 = satu socket bersama; workers dan timeout lalu diabaikan klien).
 * Kunci tambahan (di luar rentang di-clamp, peringatan masuk Log):
 *   qname (60..253, nilai tetap tanpa turun otomatis), queue (128..4096), window (32..2048),
 *   openstream (5s..60s), idle (15s..120s).
 * Nilai tidak valid diabaikan dan dilaporkan di warnings; tanpa opsi, hasilnya sama dengan sebelumnya.
 */
object SlowDnsUdpOptions {
    const val SEPARATOR = '|'

    class Parsed(
        val address: String,
        val flags: List<String>,
        val summary: String,
        val warnings: List<String>,
        val qname: Int? = null,
    )

    private val durationRe = Regex("^([0-9]{1,5})(ms|s)$")
    private val KNOWN_KEYS = setOf("rps", "workers", "timeout", "shared", "qname", "queue", "window", "openstream", "idle")
    const val QNAME_MIN = 60
    const val QNAME_MAX = 253
    private const val QUEUE_DEFAULT = 512 // bawaan klien (turbotunnel.QueueSize)
    private const val QUEUE_MAX = 4096

    private fun clampInt(v: String, min: Int, max: Int, key: String, warnings: MutableList<String>): Int? {
        val n = v.toIntOrNull()
        if (n == null) {
            warnings += "$key=$v tidak valid, diabaikan"
            return null
        }
        if (n < min || n > max) {
            warnings += "$key=$n di luar $min..$max, di-clamp"
            return n.coerceIn(min, max)
        }
        return n
    }

    private fun durationMs(v: String): Long? = durationRe.matchEntire(v)?.let {
        it.groupValues[1].toLong() * (if (it.groupValues[2] == "s") 1000L else 1L)
    }

    private fun clampMs(v: String, minMs: Int, maxMs: Int, key: String, warnings: MutableList<String>): Int? {
        val ms = durationMs(v)
        if (ms == null) {
            warnings += "$key=$v tidak valid (contoh 20s atau 20000ms), diabaikan"
            return null
        }
        if (ms < minMs || ms > maxMs) {
            warnings += "$key=$v di luar ${minMs}ms..${maxMs}ms, di-clamp"
        }
        return ms.coerceIn(minMs.toLong(), maxMs.toLong()).toInt()
    }

    fun parse(raw: String, defaultTimeout: String): Parsed {
        val idx = raw.indexOf(SEPARATOR)
        val address = (if (idx >= 0) raw.substring(0, idx) else raw).trim()
        val optText = if (idx >= 0) raw.substring(idx + 1) else ""

        val warnings = mutableListOf<String>()
        val values = linkedMapOf<String, String>()
        for (part in optText.split(',')) {
            val t = part.trim()
            if (t.isEmpty()) continue
            val eq = t.indexOf('=')
            if (eq <= 0) {
                warnings += "opsi '$t' diabaikan (format harus kunci=nilai)"
                continue
            }
            values[t.substring(0, eq).trim().lowercase()] = t.substring(eq + 1).trim()
        }
        for (k in values.keys) {
            if (k !in KNOWN_KEYS) warnings += "opsi '$k' tidak dikenal, diabaikan"
        }

        val shared = when (values["shared"]?.lowercase()) {
            null, "0", "false", "no", "off" -> false
            "1", "true", "yes", "on" -> true
            else -> {
                warnings += "shared=${values["shared"]} tidak valid, diabaikan"
                false
            }
        }

        val rps = values["rps"]?.let { v ->
            val d = v.toDoubleOrNull()
            if (d != null && d > 0.0 && d <= 10000.0) v else {
                warnings += "rps=$v tidak valid (harus 1-10000), diabaikan"
                null
            }
        }

        val workers = values["workers"]?.let { v ->
            val n = v.toIntOrNull()
            if (n != null && n in 1..500) n else {
                warnings += "workers=$v tidak valid (harus 1-500), diabaikan"
                null
            }
        }

        val timeout = values["timeout"]?.let { v ->
            val m = durationRe.matchEntire(v)
            val ms = m?.let { it.groupValues[1].toLong() * (if (it.groupValues[2] == "s") 1000L else 1L) }
            if (ms != null && ms in 100L..10000L) v else {
                warnings += "timeout=$v tidak valid (100ms-10s, contoh 1500ms atau 2s), diabaikan"
                null
            }
        }

        val qname = values["qname"]?.let { clampInt(it, QNAME_MIN, QNAME_MAX, "qname", warnings) }
        var queue = values["queue"]?.let { clampInt(it, 128, QUEUE_MAX, "queue", warnings) }
        val window = values["window"]?.let { clampInt(it, 32, 2048, "window", warnings) }
        val openStreamMs = values["openstream"]?.let { clampMs(it, 5_000, 60_000, "openstream", warnings) }
        val idleMs = values["idle"]?.let { clampMs(it, 15_000, 120_000, "idle", warnings) }
        // Klien menolak -kcp-window-size > -queue-size dan langsung keluar, jadi queue dinaikkan.
        if (window != null && window > (queue ?: QUEUE_DEFAULT)) {
            val raised = (window * 2).coerceAtMost(QUEUE_MAX)
            warnings += "window $window lebih besar dari queue ${queue ?: QUEUE_DEFAULT}, queue dinaikkan ke $raised"
            queue = raised
        }

        val flags = mutableListOf<String>()
        val parts = mutableListOf<String>()
        if (shared) {
            flags += "-udp-shared-socket"
            parts += "shared-socket"
            if (workers != null || timeout != null) {
                warnings += "workers/timeout diabaikan klien saat shared=1"
            }
        } else {
            val t = timeout ?: defaultTimeout
            flags += listOf("-udp-timeout", t)
            parts += "timeout=$t"
            if (workers != null) {
                flags += listOf("-udp-workers", workers.toString())
                parts += "workers=$workers"
            }
        }
        if (rps != null) {
            flags += listOf("-rps", rps)
            parts += "rps=$rps"
        }
        queue?.let { flags += listOf("-queue-size", it.toString()); parts += "queue=$it" }
        window?.let { flags += listOf("-kcp-window-size", it.toString()); parts += "window=$it" }
        openStreamMs?.let { flags += listOf("-open-stream-timeout", "${it}ms"); parts += "openstream=${it}ms" }
        idleMs?.let { flags += listOf("-idle-timeout", "${it}ms"); parts += "idle=${it}ms" }
        qname?.let { parts += "qname=$it" }
        return Parsed(address, flags, parts.joinToString(" "), warnings, qname)
    }
}
