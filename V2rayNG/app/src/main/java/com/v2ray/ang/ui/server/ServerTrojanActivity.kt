package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.compose.FormTextField

class ServerTrojanActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.TROJAN

    @Composable
    override fun ScreenContent() {
        val options = rememberFieldOptions()
        val scope = rememberCoroutineScope()
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(
                initialConfig = initialConfig
            )
        }.apply {
            configType = EConfigType.TROJAN
        }
        val slowDns = rememberSlowDnsFormState(initialConfig)

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = {
                if (!slowDns.isValid()) {
                    toast("NS Domain dan Public Key SlowDNS wajib diisi")
                } else {
                    // toProfileItem() memakai initialConfig.copy(...), jadi kolom SlowDNS ikut tersimpan.
                    slowDns.applyTo(initialConfig)
                    saveServer(uiState)
                }
            }
        ) {
            CommonBasicFields(uiState)
            TrojanProtocolFields(uiState)
            CommonNetworkFields(uiState, options)
            CommonStreamSecurityFields(
                state = uiState,
                options = options,
                scope = scope,
                buildProfileItem = { uiState.toProfileItem(initialConfig) }
            )
            SlowDnsFields(slowDns)
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (config.password.isNullOrBlank()) {
            return false
        }
        // Trojan tanpa TLS (mis. WebSocket di port 80 di belakang CDN) sah: security boleh kosong/none
        return true
    }

    @Composable
    private fun TrojanProtocolFields(state: ServerUiState) {
        FormTextField(
            label = stringResource(R.string.server_lab_id3),
            value = state.password,
            onValueChange = { state.password = it },
            isError = state.isPasswordError
        )
    }
}
