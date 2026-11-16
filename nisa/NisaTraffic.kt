package com.v2ray.ang.util

import android.content.Context
import android.content.Intent

/**
 * Jembatan data kecepatan trafik: service (NotificationManager) -> layar utama.
 * Pakai broadcast eksplisit ke paket sendiri, jadi aman walau service dan UI
 * berjalan di proses berbeda.
 */
object NisaTraffic {
    const val ACTION = "com.v2ray.ang.NISA_SPEED"
    const val EXTRA_UP = "nisa_up"
    const val EXTRA_DOWN = "nisa_down"

    /** Kecepatan dalam byte per detik. */
    fun publish(context: Context?, up: Double, down: Double) {
        val c = context ?: return
        try {
            c.sendBroadcast(
                Intent(ACTION)
                    .setPackage(c.packageName)
                    .putExtra(EXTRA_UP, up)
                    .putExtra(EXTRA_DOWN, down)
            )
        } catch (_: Exception) {
            // grafik hanya pelengkap, jangan sampai mengganggu service
        }
    }
}
