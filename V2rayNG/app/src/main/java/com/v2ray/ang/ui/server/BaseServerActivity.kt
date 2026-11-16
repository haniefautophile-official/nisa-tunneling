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
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager

abstract class BaseServerActivity : AppCompatActivity() {

    abstract val serverConfigType: EConfigType

    var initialConfig: ProfileItem = ProfileItem.create(EConfigType.VMESS)
        private set
        
    // Variabel untuk menyimpan ID profil dari Intent
    var currentGuid: String = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ambil guid dari Intent
        currentGuid = intent.getStringExtra("guid") ?: ""
        if (currentGuid.isNotEmpty()) {
            MmkvManager.decodeServerConfig(currentGuid)?.let {
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

    @OptIn(ExperimentalMaterial3Api::class)
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

        // Validasi konfigurasi
        if (!validateProtocolConfig(profileItem)) {
            this.toast("Konfigurasi tidak valid")
            return
        }

        // Simpan menggunakan currentGuid. Karena fungsi mengembalikan String, kita cek apakah isinya kosong atau tidak.
        val savedGuid = MmkvManager.encodeServerConfig(currentGuid, profileItem)
        if (savedGuid.isNotEmpty()) {
            this.toast("Berhasil disimpan")
            finish()
        } else {
            this.toast("Gagal menyimpan")
        }
    }
}
