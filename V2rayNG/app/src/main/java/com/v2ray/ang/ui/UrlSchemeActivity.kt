package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseComponentActivity() {

    private companion object {
        const val MAX_NISA_FILE_BYTES = 512 * 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NISA_OPEN_FILE_V1: file .nisa dari "Buka dengan" (VIEW content/file) atau Bagikan (SEND + EXTRA_STREAM)
        val fileUri = nisaFileUri(intent)
        if (fileUri != null) {
            importNisaFile(fileUri)
            return
        }
        // Impor selesai dulu, baru layar utama dibuka, supaya daftar yang dimuat sudah berisi config baru.
        lifecycleScope.launch {
            try {
                intent.apply {
                    if (action == Intent.ACTION_SEND) {
                        if ("text/plain" == type) {
                            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                                parseUri(it, null)
                            }
                        }
                    } else if (action == Intent.ACTION_VIEW) {
                        when (data?.host) {
                            "install-config" -> {
                                val uri: Uri? = intent.data
                                val shareUrl = uri?.getQueryParameter("url").orEmpty()
                                parseUri(shareUrl, uri?.fragment)
                            }

                            "install-sub" -> {
                                val uri: Uri? = intent.data
                                val shareUrl = uri?.getQueryParameter("url").orEmpty()
                                parseUri(shareUrl, uri?.fragment)
                            }

                            else -> {
                                toastError(R.string.toast_failure)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
            }
            startActivity(Intent(this@UrlSchemeActivity, MainActivity::class.java))
            finish()
        }
    }

    @Composable
    override fun ScreenContent() {
    }

    private fun nisaFileUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }
        Intent.ACTION_SEND -> if (intent.type == "text/plain") null
        else IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

    /** Baca isi file di thread IO (maks 512 KB), impor lewat jalur yang sama dengan menu Import, lalu buka layar utama. */
    private fun importNisaFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val count = try {
                val text = contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var tooBig = false
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        if (out.size() + n > MAX_NISA_FILE_BYTES) { tooBig = true; break }
                        out.write(chunk, 0, n)
                    }
                    if (tooBig) null else out.toString(Charsets.UTF_8.name())
                }
                if (text.isNullOrBlank()) 0 else {
                    val (configs, subs) = AngConfigManager.importBatchConfig(text, "", false)
                    configs + subs
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to import opened config file", e)
                0
            }
            withContext(Dispatchers.Main) {
                if (count > 0) toast(R.string.import_subscription_success)
                else toastError(R.string.import_subscription_failure)
                startActivity(Intent(this@UrlSchemeActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    private suspend fun parseUri(uriString: String?, fragment: String?) {
        if (uriString.isNullOrEmpty()) {
            return
        }
        LogUtil.i(AppConfig.TAG, uriString)

        var decodedUrl = URLDecoder.decode(uriString, "UTF-8")
        val uri = Uri.parse(decodedUrl)
        if (uri != null) {
            if (uri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
                decodedUrl += "#${fragment}"
            }
            LogUtil.i(AppConfig.TAG, decodedUrl)
            val (count, countSub) = withContext(Dispatchers.IO) {
                AngConfigManager.importBatchConfig(decodedUrl, "", false)
            }
            if (count + countSub > 0) {
                toast(R.string.import_subscription_success)
            } else {
                toast(R.string.import_subscription_failure)
            }
        }
    }
}
