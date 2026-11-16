package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.FormTextField

/** Profil SSH: kolom Host, Port, Username, Password terpisah, plus mode SlowDNS (DNSTT). */
class ServerSshActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.SSH

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig).also {
                if (initialConfig.serverPort.isNullOrBlank()) it.port = "22"
            }
        }.apply {
            configType = EConfigType.SSH
        }

        // ServerUiState tidak menyimpan username untuk tipe SSH, jadi dipegang di sini.
        var username by rememberSaveable { mutableStateOf(initialConfig.username ?: "") }
        var userError by rememberSaveable { mutableStateOf(false) }

        var slowDnsEnabled by rememberSaveable { mutableStateOf(initialConfig.slowDnsEnabled) }
        var slowDnsDomain by rememberSaveable { mutableStateOf(initialConfig.slowDnsDomain ?: "") }
        var slowDnsPubkey by rememberSaveable { mutableStateOf(initialConfig.slowDnsPubkey ?: "") }
        var slowDnsDoh by rememberSaveable { mutableStateOf(initialConfig.slowDnsDoh ?: "") }

        var payloadEnabled by rememberSaveable { mutableStateOf(!initialConfig.sshPayload.isNullOrBlank()) }
        var sshPayload by rememberSaveable { mutableStateOf(initialConfig.sshPayload ?: "") }
        var tlsEnabled by rememberSaveable { mutableStateOf(initialConfig.sshTls) }
        var sshSni by rememberSaveable { mutableStateOf(initialConfig.sshSni ?: "") }
        var proxyEnabled by rememberSaveable { mutableStateOf(!initialConfig.sshProxy.isNullOrBlank()) }
        var sshProxy by rememberSaveable { mutableStateOf(initialConfig.sshProxy ?: "") }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = {
                userError = username.isBlank()
                saveSsh(
                    uiState, username, slowDnsEnabled, slowDnsDomain, slowDnsPubkey, slowDnsDoh,
                    if (payloadEnabled) sshPayload else "",
                    tlsEnabled, sshSni,
                    if (proxyEnabled && !slowDnsEnabled) sshProxy else ""
                )
            }
        ) {
            FormTextField(
                label = "Remarks",
                value = uiState.remarks,
                onValueChange = { uiState.remarks = it },
                isError = uiState.isRemarksError
            )
            FormTextField(
                label = "Host",
                value = uiState.address,
                onValueChange = { uiState.address = it },
                isError = uiState.isAddressError
            )
            FormTextField(
                label = "Port",
                value = uiState.port,
                onValueChange = { uiState.port = it },
                keyboardType = KeyboardType.Number,
                isError = uiState.isPortError
            )
            FormTextField(
                label = "Username",
                value = username,
                onValueChange = { username = it },
                isError = userError
            )
            FormTextField(
                label = "Password",
                value = uiState.password,
                onValueChange = { uiState.password = it },
                isError = uiState.isPasswordError
            )

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = payloadEnabled, onCheckedChange = { payloadEnabled = it })
                Text("Payload kustom")
            }
            if (payloadEnabled) {
                FormTextField(
                    label = "Payload",
                    value = sshPayload,
                    onValueChange = { sshPayload = it },
                    placeholder = "GET / HTTP/1.1[crlf]Host: bug.host[crlf]Upgrade: websocket[crlf][crlf]",
                    supportingText = "Placeholder: [crlf] [lf] [cr] [host] [port] [host_port] [protocol] [split]"
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = tlsEnabled, onCheckedChange = { tlsEnabled = it })
                Text("TLS (SNI)")
            }
            if (tlsEnabled) {
                FormTextField(
                    label = "SNI (kosong = pakai Host)",
                    value = sshSni,
                    onValueChange = { sshSni = it }
                )
            }
            if (!slowDnsEnabled) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
                    Text("Proxy HTTP")
                }
                if (proxyEnabled) {
                    FormTextField(
                        label = "Proxy ([user:pass@]host:port)",
                        value = sshProxy,
                        onValueChange = { sshProxy = it }
                    )
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = slowDnsEnabled, onCheckedChange = { slowDnsEnabled = it })
                Text("Aktifkan SlowDNS (DNSTT)")
            }
            if (slowDnsEnabled) {
                FormTextField(
                    label = "NS Domain (contoh: ns.domain.com)",
                    value = slowDnsDomain,
                    onValueChange = { slowDnsDomain = it }
                )
                FormTextField(
                    label = "Public Key Server",
                    value = slowDnsPubkey,
                    onValueChange = { slowDnsPubkey = it }
                )
                FormTextField(
                    label = "DNS / DoH (opsional)",
                    value = slowDnsDoh,
                    onValueChange = { slowDnsDoh = it }
                )
            }
        }
    }

    private fun saveSsh(
        state: ServerUiState,
        username: String,
        slowDnsEnabled: Boolean,
        slowDnsDomain: String,
        slowDnsPubkey: String,
        slowDnsDoh: String,
        payload: String,
        tls: Boolean,
        sni: String,
        proxy: String
    ): Boolean {
        if (!validateBasicConfig(state)) return false
        if (username.isBlank()) {
            toast("Username SSH wajib diisi")
            return false
        }
        if (state.password.isBlank()) {
            state.isPasswordError = true
            toast("Password SSH wajib diisi")
            return false
        }
        if (slowDnsEnabled && (slowDnsDomain.isBlank() || slowDnsPubkey.isBlank())) {
            toast("NS Domain dan Public Key SlowDNS wajib diisi")
            return false
        }

        val config = state.toProfileItem(initialConfig).copy(
            username = username.trim(),
            method = null,
            slowDnsEnabled = slowDnsEnabled,
            slowDnsDomain = slowDnsDomain.trim(),
            slowDnsPubkey = slowDnsPubkey.trim(),
            slowDnsDoh = slowDnsDoh.trim(),
            sshPayload = payload.trim().ifEmpty { null },
            sshTls = tls,
            sshSni = sni.trim().ifEmpty { null },
            sshProxy = proxy.trim().ifEmpty { null }
        )
        config.description = AngConfigManager.generateDescription(config)
        if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) {
            config.subscriptionId = subscriptionId.orEmpty()
        }
        val savedGuid = MmkvManager.encodeServerConfig(editGuid, config)
        toastSuccess(R.string.toast_success)
        ProfileEditorResult.run {
            finishSaved(savedGuid, isRunning)
        }
        return true
    }
}
