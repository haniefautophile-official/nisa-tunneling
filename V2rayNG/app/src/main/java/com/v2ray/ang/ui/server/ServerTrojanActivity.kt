package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
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

        // State untuk mengontrol munculnya dialog SlowDNS
        var showSlowDnsDialog by remember { mutableStateOf(false) }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveServer(uiState) }
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
            
            // Tombol untuk membuka pengaturan SlowDNS
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { showSlowDnsDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(text = "Pengaturan SlowDNS (DNSTT)")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Dialog SlowDNS
        if (showSlowDnsDialog) {
            SlowDnsDialog(
                uiState = uiState,
                onDismiss = { showSlowDnsDialog = false }
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (config.password.isNullOrBlank()) {
            return false
        }
        if (config.security.isNullOrBlank()) {
            return false
        }
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

    // --- TAMBAHAN DIALOG SLOW DNS ---
    @Composable
    private fun SlowDnsDialog(
        uiState: ServerUiState,
        onDismiss: () -> Unit
    ) {
        var isEnabled by remember { mutableStateOf(uiState.slowDnsEnabled) }
        var domain by remember { mutableStateOf(uiState.slowDnsDomain ?: "") }
        var pubkey by remember { mutableStateOf(uiState.slowDnsPubkey ?: "") }
        var doh by remember { mutableStateOf(uiState.slowDnsDoh ?: "https://cloudflare-dns.com/dns-query") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Pengaturan SlowDNS (DNSTT)") },
            text = {
                Column {
                    // Checkbox Aktifkan
                    androidx.compose.foundation.layout.Row {
                        Checkbox(
                            checked = isEnabled,
                            onCheckedChange = { isEnabled = it }
                        )
                        Text(
                            text = "Aktifkan Mode SlowDNS",
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text("NS Domain (contoh: ns.domain.com)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = pubkey,
                        onValueChange = { pubkey = it },
                        label = { Text("Public Key Server") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = doh,
                        onValueChange = { doh = it },
                        label = { Text("DoH URL (opsional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        uiState.slowDnsEnabled = isEnabled
                        uiState.slowDnsDomain = domain
                        uiState.slowDnsPubkey = pubkey
                        uiState.slowDnsDoh = doh
                        onDismiss()
                    }
                ) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Batal")
                }
            }
        )
    }
}
