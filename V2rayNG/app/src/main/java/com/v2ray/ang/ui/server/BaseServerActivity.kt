package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils

abstract class BaseServerActivity : AppCompatActivity() {

    abstract val serverConfigType: EConfigType

    var initialConfig: ProfileItem = ProfileItem.create(EConfigType.VMESS)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val guid = intent.getStringExtra("guid") ?: ""
        if (guid.isNotEmpty()) {
            MmkvManager.decodeServerConfig(guid)?.let {
                initialConfig = it
            }
        }

        setContent {
            ScreenContent()
        }
    }

    @Composable
    abstract fun ScreenContent()

    abstract fun validateProtocolConfig(config: ProfileItem): Boolean

    @OptIn(ExperimentalMaterial3Api::class) // <--- INI OBATNYA
    @Composable
    fun ServerEditorScaffold(
        title: String,
        onSaveClick: () -> Unit,
        content: @Composable () -> Unit
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    actions = {
                        TextButton(onClick = onSaveClick) {
                            Text("Simpan")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        }
    }

    fun saveServer(uiState: ServerUiState) {
        val profileItem = uiState.toProfileItem(initialConfig)

        if (!validateProtocolConfig(profileItem)) {
            Utils.toast("Konfigurasi tidak valid")
            return
        }

        val guid = profileItem.guid
        if (MmkvEncoder.encodeServerConfig(guid, profileItem)) { // perbaikan typo
            Utils.toast("Berhasil disimpan")
            finish()
        } else {
            Utils.toast("Gagal menyimpan")
        }
    }
}
