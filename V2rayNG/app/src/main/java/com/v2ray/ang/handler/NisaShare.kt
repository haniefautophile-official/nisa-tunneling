package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.NisaCrypt

/**
 * Ekspor/impor akun NISA.
 *
 * - File .nisa standar  : JSON {"nisa":1,"profiles":[ProfileItem lengkap]}
 * - File .nisa terkunci : {"nisa":1,"lock":true,"data":"<blob>"}
 * - Link terkunci       : nisa://<blob>
 * - Link standar        : vmess://, vless://, ssh://, dst (dibuat AngConfigManager)
 *
 * Terkunci tetap bisa diimpor tanpa password; akun hasil impor ditandai locked dan isinya disembunyikan.
 * Akun yang sudah terkunci tidak pernah bisa diekspor lagi.
 */
object NisaShare {
    const val FILE_EXT = "nisa"
    const val LOCKED_DESCRIPTION = "\uD83D\uDD12 Terkunci"

    data class Payload(var nisa: Int = 1, var profiles: List<Entry>? = null)
    data class Entry(var profile: ProfileItem? = null)
    private data class Envelope(var nisa: Int? = null, var lock: Boolean? = null, var data: String? = null)

    /** JSON akun lengkap. Akun terkunci dilewati. */
    fun payloadJson(guids: List<String>): String? {
        val entries = guids.mapNotNull { MmkvManager.decodeServerConfig(it) }
            .filter { !it.locked }
            .map { Entry(it) }
        if (entries.isEmpty()) return null
        return JsonUtil.toJson(Payload(1, entries))
    }

    /** Isi file .nisa. locked=false: standar tanpa kunci. */
    fun fileText(guids: List<String>, locked: Boolean): String? {
        val json = payloadJson(guids) ?: return null
        if (!locked) return json
        return JsonUtil.toJson(Envelope(1, true, NisaCrypt.lockToText(json)))
    }

    fun lockedLink(guids: List<String>): String? {
        val json = payloadJson(guids) ?: return null
        return NisaCrypt.LINK_PREFIX + NisaCrypt.lockToText(json)
    }

    private fun envelopeOf(text: String): Envelope? {
        val t = text.trim()
        if (!t.startsWith("{") || !t.contains("\"lock\"")) return null
        return try {
            JsonUtil.fromJson(t, Envelope::class.java)?.takeIf { it.lock == true && !it.data.isNullOrEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** True jika teks adalah link/file terkunci (nisa://... atau envelope lock). */
    fun isLocked(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.trim().startsWith(NisaCrypt.LINK_PREFIX, ignoreCase = true) || envelopeOf(text) != null
    }

    /** Buka link/file terkunci menjadi JSON akun. Null jika rusak. */
    fun unlock(text: String): String? {
        val t = text.trim()
        val blob = if (t.startsWith(NisaCrypt.LINK_PREFIX, ignoreCase = true)) {
            t.substring(NisaCrypt.LINK_PREFIX.length)
        } else envelopeOf(t)?.data ?: return null
        return NisaCrypt.unlockFromText(blob)
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
