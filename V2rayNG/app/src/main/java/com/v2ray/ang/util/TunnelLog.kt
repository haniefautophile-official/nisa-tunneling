package com.v2ray.ang.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Log tunnel (SSH / SlowDNS) yang ditampilkan di layar utama, tab LOG pada kartu TRAFIK. */
object TunnelLog {
    private const val MAX_LINES = 300
    private val state = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = state

    fun add(message: String) {
        Log.i("GoLog", message)
        val time = synchronized(this) { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) }
        state.update { (it + "[$time] $message").takeLast(MAX_LINES) }
    }

    fun clear() {
        state.value = emptyList()
    }
}
