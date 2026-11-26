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
 * Nilai tidak valid diabaikan dan dilaporkan di warnings; tanpa opsi, hasilnya sama dengan sebelumnya.
 */
object SlowDnsUdpOptions {
    const val SEPARATOR = '|'

    class Parsed(
        val address: String,
        val flags: List<String>,
        val summary: String,
        val warnings: List<String>,
    )

    private val durationRe = Regex("^([0-9]{1,5})(ms|s)$")

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
            if (k !in setOf("rps", "workers", "timeout", "shared")) warnings += "opsi '$k' tidak dikenal, diabaikan"
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
        return Parsed(address, flags, parts.joinToString(" "), warnings)
    }
}
