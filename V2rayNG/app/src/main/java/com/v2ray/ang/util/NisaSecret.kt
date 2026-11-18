package com.v2ray.ang.util

/**
 * Seed lama, hanya untuk membuka backup berformat "NISA1:".
 * Saat build CI, nisa/encrypt-config.sh menimpa file ini dengan seed dari secret NISA_CONFIG_KEY.
 */
internal object NisaSecret {
    fun seed(): ByteArray = "nisa-temporary-seed".toByteArray(Charsets.UTF_8)
}
