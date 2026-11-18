package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.NisaCrypt

/**
 * Ekspor/impor akun NISA.
 *
 * - File .nisa standar : JSON {"nisa":1,"profiles":[ProfileItem lengkap]}
 * - File .nisa terenkripsi : {"nisa":1,"enc":true,"data":"<blob AES-256-GCM>"}
 * - Link terenkripsi : nisa://<blob>, isi blob = JSON file standar
 * - Link standar : vmess://, vless://, ssh://, dst (dibuat AngConfigManager)
 */
object NisaShare {
    const val FILE_EXT = "nisa"

    data class Payload(var nisa: Int = 1, var profiles: List<Entry>? = null)
    data class Entry(var profile: ProfileItem? = null)
    private data class Envelope(var nisa: Int? = null, var enc: Boolean? = null, var data: String? = null)

    fun payloadJson(guids: List<String>): String? {
        val entries = guids.mapNotNull { MmkvManager.decodeServerConfig(it) }.map { Entry(it) }
        if (entries.isEmpty()) return null
        return JsonUtil.toJson(Payload(1, entries))
    }

    /** Isi file .nisa. password null = standar tanpa kunci. */
    fun fileText(guids: List<String>, password: String?): String? {
        val json = payloadJson(guids) ?: return null
        if (password == null) return json
        return JsonUtil.toJson(Envelope(1, true, NisaCrypt.encryptToText(json, password)))
    }

    fun encryptedLink(guids: List<String>, password: String): String? {
        val json = payloadJson(guids) ?: return null
        return NisaCrypt.LINK_PREFIX + NisaCrypt.encryptToText(json, password)
    }

    private fun envelopeOf(text: String): Envelope? {
        val t = text.trim()
        if (!t.startsWith("{") || !t.contains("\"enc\"")) return null
        return try {
            JsonUtil.fromJson(t, Envelope::class.java)?.takeIf { it.enc == true && !it.data.isNullOrEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** True jika teks terenkripsi password (link nisa:// atau file terenkripsi) dan perlu diminta passwordnya. */
    fun needsPassword(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.trim().startsWith(NisaCrypt.LINK_PREFIX, ignoreCase = true) || envelopeOf(text) != null
    }

    /** Buka teks terenkripsi password. Null jika password salah. */
    fun decrypt(text: String, password: String): String? {
        val t = text.trim()
        val blob = if (t.startsWith(NisaCrypt.LINK_PREFIX, ignoreCase = true)) {
            t.substring(NisaCrypt.LINK_PREFIX.length)
        } else envelopeOf(t)?.data ?: return null
        return NisaCrypt.decryptFromText(blob, password)
    }

    /** Buka backup lama "NISA1:" otomatis; teks lain dikembalikan apa adanya. */
    fun normalizeLegacy(text: String?): String? {
        if (text == null || !NisaCrypt.isLegacy(text)) return text
        return NisaCrypt.decryptLegacy(text).orEmpty()
    }

    fun parsePayload(text: String?): List<ProfileItem> {
        val t = text?.trim().orEmpty()
        if (!t.startsWith("{") || !t.contains("\"nisa\"") || !t.contains("\"profiles\"")) return emptyList()
        return try {
            JsonUtil.fromJson(t, Payload::class.java)?.profiles.orEmpty().mapNotNull { it.profile }
                .filter { it.configType != null }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
