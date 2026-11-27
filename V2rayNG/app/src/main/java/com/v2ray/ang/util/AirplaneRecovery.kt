package com.v2ray.ang.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.core.app.NotificationCompat
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.root.RootShell
import com.v2ray.ang.service.NisaVoiceInteractionService
import kotlin.concurrent.thread

/**
 * Pemulihan koneksi macet dengan mode pesawat ON lalu OFF otomatis (mirip IP Hunter, tapi hanya saat macet).
 * Dipicu AutoPing setelah ping gagal berulang. Berhenti begitu ping berhasil lagi; maksimal [MAX_ATTEMPTS]
 * percobaan per kejadian macet. Jalur: aplikasi bantuan digital (tanpa root), cadangan root.
 */
object AirplaneRecovery {
    const val KEY_ENABLED = "pref_nisa_airplane_enabled"
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_GAP_MS = 20_000L
    private const val AIRPLANE_ON_MS = 3_000L
    private const val CELLULAR_WAIT_MS = 45_000L

    @Volatile private var busy = false
    @Volatile private var givenUp = false

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(KEY_ENABLED, false)

    /** Dipakai AutoPing: tidak memicu lagi selama pemulihan berjalan atau setelah menyerah. */
    fun isBusy(): Boolean = busy

    fun isBusyOrGivenUp(): Boolean = busy || givenUp

    /** Dipanggil AutoPing saat ping berhasil: kejadian macet dianggap selesai. */
    fun onHealthy() {
        givenUp = false
    }

    fun isAssistantActive(context: Context): Boolean = try {
        VoiceInteractionService.isActiveService(context, ComponentName(context, NisaVoiceInteractionService::class.java))
    } catch (_: Exception) {
        false
    }

    private fun airplaneOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    private fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any {
            val c = cm.getNetworkCapabilities(it) ?: return@any false
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun cellularUp(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any {
            val c = cm.getNetworkCapabilities(it) ?: return@any false
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun inCall(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun waitFor(ms: Long, step: Long = 500, cond: () -> Boolean): Boolean {
        var t = 0L
        while (t < ms) {
            if (cond()) return true
            try { Thread.sleep(step) } catch (_: InterruptedException) { return false }
            t += step
        }
        return cond()
    }

    /** Ubah mode pesawat dan pastikan benar-benar berubah. Assistant dulu, root sebagai cadangan. */
    private fun setAirplane(context: Context, on: Boolean): Boolean {
        if (isAssistantActive(context) && NisaVoiceInteractionService.requestAirplane(on)) {
            if (waitFor(8_000) { airplaneOn(context) == on }) return true
            TunnelLog.add("Mode pesawat: sesi asisten tidak mengubah status, coba root")
        }
        val script = buildString {
            append("if cmd connectivity airplane-mode ${if (on) "enable" else "disable"} >/dev/null 2>&1; then exit 0; fi\n")
            append("settings put global airplane_mode_on ${if (on) 1 else 0}\n")
            append("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on >/dev/null 2>&1\n")
        }
        val r = try { RootShell.runScript(context, "airplane.sh", script) } catch (_: Exception) { null }
        return r?.success == true && waitFor(8_000) { airplaneOn(context) == on }
    }

    /** Mulai pemulihan di thread sendiri. Aman dipanggil berulang: diabaikan jika sedang berjalan. */
    @Synchronized
    fun start(context: Context, pingOk: () -> Boolean) {
        if (busy || givenUp) return
        val app = context.applicationContext
        if (!isAssistantActive(app) && !hasRoot(app)) {
            givenUp = true
            TunnelLog.add("Mode pesawat otomatis: NISA belum dipilih sebagai aplikasi bantuan (Pengaturan > Auto on/off mode pesawat)")
            return
        }
        busy = true
        thread(isDaemon = true, name = "nisa-airplane") {
            try {
                run(app, pingOk)
            } finally {
                busy = false
            }
        }
    }

    private fun hasRoot(context: Context): Boolean = try {
        RootShell.runScript(context, "probe.sh", "id -u").output.trim().endsWith("0")
    } catch (_: Exception) {
        false
    }

    private fun run(context: Context, pingOk: () -> Boolean) {
        for (attempt in 1..MAX_ATTEMPTS) {
            if (!CoreServiceManager.isRunning()) return
            if (onWifi(context)) {
                TunnelLog.add("Mode pesawat otomatis dilewati: sedang memakai Wi-Fi")
                givenUp = true
                return
            }
            if (!waitFor(60_000, 2_000) { !inCall(context) }) return
            TunnelLog.add("Koneksi macet, mode pesawat ON/OFF (percobaan $attempt/$MAX_ATTEMPTS)")
            var airplaneSet = false
            try {
                airplaneSet = setAirplane(context, true)
                if (airplaneSet) {
                    try { Thread.sleep(AIRPLANE_ON_MS) } catch (_: InterruptedException) { return }
                }
            } finally {
                // Mode pesawat harus selalu dikembalikan OFF, apa pun hasilnya
                if (airplaneSet || airplaneOn(context)) {
                    if (!setAirplane(context, false)) TunnelLog.add("Mode pesawat: gagal mematikan, matikan manual")
                }
            }
            if (!airplaneSet) {
                TunnelLog.add("Mode pesawat: gagal dinyalakan")
                givenUp = true
                return
            }
            if (!waitFor(CELLULAR_WAIT_MS, 1_000) { cellularUp(context) }) {
                TunnelLog.add("Sinyal seluler belum kembali setelah mode pesawat OFF")
            }
            try { Thread.sleep(3_000) } catch (_: InterruptedException) { return }
            if (!CoreServiceManager.isRunning()) return
            if (SlowDnsManager.isActive()) SlowDnsManager.forceRestart()
            CoreServiceManager.reconnectForRecovery()

            // Beri waktu tunnel naik, lalu uji ping
            val tries = if (SlowDnsManager.isActive()) 6 else 4
            repeat(tries) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return }
                if (pingOk()) {
                    TunnelLog.add("Koneksi pulih setelah mode pesawat ON/OFF")
                    givenUp = false
                    return
                }
            }
            try { Thread.sleep(RETRY_GAP_MS) } catch (_: InterruptedException) { return }
        }
        givenUp = true
        TunnelLog.add("Mode pesawat otomatis berhenti: $MAX_ATTEMPTS percobaan belum memulihkan koneksi")
        notifyGiveUp(context)
    }

    private fun notifyGiveUp(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = "nisa_airplane"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(ch, "Pemulihan koneksi", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val n = NotificationCompat.Builder(context, ch)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle("Koneksi masih macet")
                .setContentText("Mode pesawat otomatis sudah $MAX_ATTEMPTS kali, server mungkin bermasalah.")
                .setAutoCancel(true)
                .build()
            nm.notify(7731, n)
        } catch (_: Exception) {
        }
    }
}
