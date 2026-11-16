package com.v2ray.ang.util

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Enkripsi config untuk ekspor/impor (AES-256-GCM).
 * Format: "NISA1:" + Base64(IV 12 byte + ciphertext + tag).
 * Teks biasa (vmess://, vless://, dst) tetap bisa diimpor seperti biasa.
 */
object NisaCrypt {
    private const val PREFIX = "NISA1:"

    private fun key(): SecretKeySpec {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(NisaSecret.seed())
        md.update("NISA-CONFIG-v1".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(md.digest(), "AES")
    }

    /** Enkripsi teks config. Jika gagal, mengembalikan string kosong (tidak pernah membocorkan plaintext). */
    fun wrap(plain: String): String {
        if (plain.isEmpty()) return plain
        return try {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP or Base64.URL_SAFE)
        } catch (e: Exception) {
            ""
        }
    }

    /** Jika teks terenkripsi NISA: kembalikan hasil dekripsi (atau "" jika gagal). Jika bukan: kembalikan apa adanya. */
    fun unwrap(text: String?): String? {
        if (text == null) return null
        val t = text.trim()
        if (!t.startsWith(PREFIX)) return text
        return try {
            val raw = Base64.decode(t.removePrefix(PREFIX), Base64.NO_WRAP or Base64.URL_SAFE)
            val iv = raw.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
