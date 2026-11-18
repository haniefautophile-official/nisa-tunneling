package com.v2ray.ang.util

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mode "Terkunci": isi akun dikompres lalu dienkripsi AES-256-GCM dengan kunci bawaan aplikasi,
 * jadi file/link bisa diimpor siapa pun yang memakai aplikasi NISA tanpa password, di build mana pun.
 * Akun hasil impor ditandai terkunci (lihat ProfileItem.locked) dan aplikasi menyembunyikan isinya.
 *
 * Catatan: kunci ada di dalam aplikasi, jadi ini penghalang untuk pengguna biasa, bukan perlindungan
 * terhadap orang yang membongkar APK.
 *
 * Blob = [versi 1B][iv 12B][ciphertext+tag]. Format lama "NISA1:" (seed build) hanya untuk membuka backup lama.
 */
object NisaCrypt {
    const val LINK_PREFIX = "nisa://"
    private const val LEGACY_PREFIX = "NISA1:"
    private const val VERSION: Byte = 3
    private const val IV_LEN = 12
    private const val TAG_LEN = 16
    private const val B64 = Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE

    private fun lockKey(): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("nisa-tunneling/locked-config/v3".toByteArray(Charsets.UTF_8))

    fun lockBytes(plain: ByteArray): ByteArray {
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(lockKey(), "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(byteArrayOf(VERSION))
        return byteArrayOf(VERSION) + iv + c.doFinal(packed)
    }

    /** Null jika data rusak/bukan blob terkunci. */
    fun unlockBytes(blob: ByteArray): ByteArray? = try {
        if (blob.size < 1 + IV_LEN + TAG_LEN || blob[0] != VERSION) null else {
            val iv = blob.copyOfRange(1, 1 + IV_LEN)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(lockKey(), "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(byteArrayOf(VERSION))
            val packed = c.doFinal(blob, 1 + IV_LEN, blob.size - 1 - IV_LEN)
            GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
        }
    } catch (e: Exception) {
        null
    }

    fun lockToText(plain: String): String = Base64.encodeToString(lockBytes(plain.toByteArray(Charsets.UTF_8)), B64)

    fun unlockFromText(b64: String): String? = try {
        unlockBytes(Base64.decode(b64.trim(), B64))?.toString(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun isLegacy(text: String?): Boolean = text?.trim()?.startsWith(LEGACY_PREFIX) == true

    /** Buka backup lama "NISA1:" (seed build). Mengembalikan null jika gagal. */
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
}
