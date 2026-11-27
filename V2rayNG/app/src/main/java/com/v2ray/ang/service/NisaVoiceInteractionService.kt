package com.v2ray.ang.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.View

/**
 * Jalur tanpa root untuk menyalakan/mematikan mode pesawat (cara yang sama dipakai aplikasi seperti HTTP Custom):
 * NISA didaftarkan sebagai "aplikasi bantuan digital". Aplikasi bantuan boleh memanggil
 * Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE lewat sesi suara. User cukup memilih NISA sebagai aplikasi bantuan
 * default satu kali (Pengaturan > Auto on/off mode pesawat).
 */
class NisaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        super.onShutdown()
        if (instance === this) instance = null
    }

    companion object {
        const val EXTRA_AIRPLANE = "nisa_airplane"
        const val EXTRA_ON = "nisa_airplane_on"

        @Volatile
        var instance: NisaVoiceInteractionService? = null

        /** Minta sesi suara mengubah mode pesawat. False jika NISA belum jadi aplikasi bantuan aktif. */
        fun requestAirplane(on: Boolean): Boolean {
            val s = instance ?: return false
            val args = Bundle().apply {
                putBoolean(EXTRA_AIRPLANE, true)
                putBoolean(EXTRA_ON, on)
            }
            return try {
                Handler(Looper.getMainLooper()).post {
                    try { s.showSession(args, 0) } catch (_: Exception) { }
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}

class NisaVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NisaVoiceSession(this)
}

class NisaVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreateContentView(): View? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (args?.getBoolean(NisaVoiceInteractionService.EXTRA_AIRPLANE) != true) {
            // Pengguna menekan lama Home/tombol daya: tidak ada yang ditampilkan
            hide()
            return
        }
        val on = args.getBoolean(NisaVoiceInteractionService.EXTRA_ON)
        val intent = Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE)
            .addCategory(Intent.CATEGORY_VOICE)
            .putExtra(Settings.EXTRA_AIRPLANE_MODE_ENABLED, on)
        try {
            startVoiceActivity(intent)
        } catch (_: Exception) {
            hide()
        }
    }

    // Layar Pengaturan meminta konfirmasi lewat VoiceInteractor: jawab setuju otomatis
    override fun onRequestConfirmation(request: ConfirmationRequest) {
        request.sendConfirmationResult(true, null)
    }

    override fun onRequestCompleteVoice(request: CompleteVoiceRequest) {
        request.sendCompleteVoiceResult(null)
        hide()
    }

    override fun onRequestAbortVoice(request: AbortVoiceRequest) {
        request.sendAbortVoiceResult(null)
        hide()
    }

    override fun onRequestCommand(request: CommandRequest) {
        request.sendResult(null)
    }

    override fun onCancelRequest(request: Request) {}
}

/** Hanya pelengkap: sebagian versi Android mewajibkan recognitionService pada layanan suara. */
class NisaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        try { listener.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) { }
    }

    override fun onCancel(listener: Callback) {}
    override fun onStopListening(listener: Callback) {}
}
