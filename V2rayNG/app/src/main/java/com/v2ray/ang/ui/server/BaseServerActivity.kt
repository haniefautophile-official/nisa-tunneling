package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

abstract class BaseServerActivity : AppCompatActivity() {

    abstract val serverConfigType: EConfigType

    var initialConfig: ProfileItem = ProfileItem.create(EConfigType.VMESS)
        private set

    companion object {
        // Variabel statis untuk menerima data dari form SlowDNS
        var tempSlowDnsEnabled = false
        var tempSlowDnsDomain: String? = null
        var tempSlowDnsPubkey: String? = null
        var tempSlowDnsDoh: String? = null

        // Flag untuk menandai apakah form SlowDNS sudah diisi
        var hasTempSlowDns = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Reset data sementara setiap kali form dibuka
        hasTempSlowDns = false
        tempSlowDnsEnabled = false
        tempSlowDnsDomain = null
        tempSlowDnsPubkey = null
        tempSlowDnsDoh = null

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
                        androidx.compose.material3.TextButton(onClick = onSaveClick) {
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

        // --- TAMBAHAN SLOW DNS ---
        // Jika form SlowDNS diisi dari ServerTrojanActivity, masukkan datanya ke ProfileItem
        if (hasTempSlowDns) {
            profileItem.slowDnsEnabled = tempSlowDnsEnabled
            profileItem.slowDnsDomain = tempSlowDnsDomain
            profileItem.slowDnsPubkey = tempSlowDnsPubkey
            profileItem.slowDnsDoh = tempSlowDnsDoh
        }
        // -------------------------

        if (!validateProtocolConfig(profileItem)) {
            Utils.toast("Konfigurasi tidak valid")
            return
        }

        val guid = profileItem.guid
        if (MmkvManager.encodeServerConfig(guid, profileItem)) {
            Utils.toast("Berhasil disimpan")
            finish()
        } else {
            Utils.toast("Gagal menyimpan")
        }
    }
}
