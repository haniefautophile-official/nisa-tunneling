package com.v2ray.ang.util

import android.util.Base64
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Enkripsi ekspor/impor config.
 *
 * Mode baru (v2): AES-256-GCM, kunci dari password pengguna lewat PBKDF2-HMAC-SHA256.
 * Blob = [versi 1B][iterasi 4B][salt 16B][iv 12B][ciphertext+tag]; header ikut diautentikasi (AAD).
 * Blob bisa dibuka di build mana pun selama password benar.
 *
 * Mode lama "NISA1:" (kunci dari seed build) hanya dipertahankan untuk membuka backup lama.
 */
object NisaCrypt {
    const val LINK_PREFIX = "nisa://"
    private const val LEGACY_PREFIX = "NISA1:"
    private const val VERSION: Byte = 2
    private const val ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 1_000_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val HEADER_LEN = 5
    private const val TAG_LEN = 16
    private const val B64 = Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE

    fun encryptBytes(plain: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "password kosong" }
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_LEN).put(VERSION).putInt(ITERATIONS).array()
        val key = pbkdf2(password.toByteArray(Charsets.UTF_8), salt, ITERATIONS)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(header)
        return header + salt + iv + c.doFinal(plain)
    }

    /** Mengembalikan null jika password salah atau data rusak. */
    fun decryptBytes(blob: ByteArray, password: String): ByteArray? {
        return try {
            if (password.isEmpty() || blob.size < HEADER_LEN + SALT_LEN + IV_LEN + TAG_LEN) return null
            if (blob[0] != VERSION) return null
            val iter = ByteBuffer.wrap(blob, 1, 4).int
            if (iter !in 1..MAX_ITERATIONS) return null
            val header = blob.copyOfRange(0, HEADER_LEN)
            val salt = blob.copyOfRange(HEADER_LEN, HEADER_LEN + SALT_LEN)
            val iv = blob.copyOfRange(HEADER_LEN + SALT_LEN, HEADER_LEN + SALT_LEN + IV_LEN)
            val off = HEADER_LEN + SALT_LEN + IV_LEN
            val key = pbkdf2(password.toByteArray(Charsets.UTF_8), salt, iter)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(header)
            c.doFinal(blob, off, blob.size - off)
        } catch (e: Exception) {
            null
        }
    }

    fun encryptToText(plain: String, password: String): String =
        Base64.encodeToString(encryptBytes(plain.toByteArray(Charsets.UTF_8), password), B64)

    fun decryptFromText(b64: String, password: String): String? = try {
        decryptBytes(Base64.decode(b64.trim(), B64), password)?.toString(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun isLegacy(text: String?): Boolean = text?.trim()?.startsWith(LEGACY_PREFIX) == true

    /** Buka format lama "NISA1:" (seed build). Mengembalikan null jika gagal. */
    fun decryptLegacy(text: String): String? = try {
        val raw = Base64.decode(text.trim().removePrefix(LEGACY_PREFIX), Base64.NO_WRAP or Base64.URL_SAFE)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(NisaSecret.seed())
        md.update("NISA-CONFIG-v1".toByteArray(Charsets.UTF_8))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(md.digest(), "AES"), GCMParameterSpec(128, raw.copyOfRange(0, IV_LEN)))
        String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** PBKDF2-HMAC-SHA256, satu blok (32 byte). Ditulis manual karena PBKDF2WithHmacSHA256 baru ada di API 26. */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 2..iterations) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }
}
