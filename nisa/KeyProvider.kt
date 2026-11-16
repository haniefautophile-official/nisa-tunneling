package com.v2ray.ang.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.v2ray.ang.AngApplication
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Menyediakan kunci enkripsi MMKV (untuk AppConfig.MMKV_CRYPT_KEY_LEN byte).
 * Kunci dibuat acak sekali per instalasi, lalu disimpan dalam bentuk
 * terenkripsi (AES-GCM) memakai kunci master di Android Keystore.
 * Kunci master itu sendiri tidak pernah bisa diekspor dari perangkat,
 * sehingga file config MMKV tidak bisa langsung di-decode walau
 * data aplikasi diekstrak (misal lewat adb backup atau root).
 */
object KeyProvider {

    private const val KEYSTORE_ALIAS = "nisa_mmkv_master"
    private const val PREFS_NAME = "nisa_key_store"
    private const val PREF_ENC_KEY = "enc_key"
    private const val PREF_IV = "enc_iv"
    private const val KEY_LEN_BYTES = 16 // AES-128, batas internal MMKV

    @Volatile
    private var cached: String? = null

    fun getMmkvCryptKey(): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val key = loadOrCreateKey()
            cached = key
            return key
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadOrCreateKey(): String {
        val context = AngApplication.application
        val p = prefs(context)
        val encB64 = p.getString(PREF_ENC_KEY, null)
        val ivB64 = p.getString(PREF_IV, null)

        if (encB64 != null && ivB64 != null) {
            runCatching {
                return decrypt(Base64.decode(encB64, Base64.NO_WRAP), Base64.decode(ivB64, Base64.NO_WRAP))
            }
            // Jika gagal (misal keystore direset oleh sistem), buat ulang kunci baru di bawah.
        }

        val raw = ByteArray(KEY_LEN_BYTES).also { SecureRandom().nextBytes(it) }
        val keyString = Base64.encodeToString(raw, Base64.NO_WRAP).take(KEY_LEN_BYTES)

        val (cipherText, iv) = encrypt(keyString.toByteArray(Charsets.UTF_8))
        p.edit {
            putString(PREF_ENC_KEY, Base64.encodeToString(cipherText, Base64.NO_WRAP))
            putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
        }
        return keyString
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        return cipher.doFinal(plain) to cipher.iv
    }

    private fun decrypt(cipherText: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
