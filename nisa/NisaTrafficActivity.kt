package com.v2ray.ang.ui

import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.NisaTrafficScreen

/** Layar terpisah "Trafik & Log" (kecepatan, grafik, dan kotak log tunnel). */
class NisaTrafficActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        NisaTrafficScreen(onBackClick = { finish() })
    }
}
