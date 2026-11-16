package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
        
        // Menyimpan data sementara menggunakan state lokal Compose
        var slowDnsEnabled by remember { mutableStateOf(initialConfig.slowDnsEnabled) }
        var slowDnsDomain by remember { mutableStateOf(initialConfig.slowDnsDomain ?: "") }
        var slowDnsPubkey by remember { mutableStateOf(initialConfig.slowDnsPubkey ?: "") }
        var slowDnsDoh by remember { mutableStateOf(initialConfig.slowDnsDoh ?: "https://cloudflare-dns.com/dns-query") }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { 
                // Pindahkan data SlowDNS ke objek uiState sebelum disimpan
                uiState.slowDnsEnabled = slowDnsEnabled
                uiState.slowDnsDomain = slowDnsDomain
                uiState.slowDnsPubkey = slowDnsPubkey
                uiState.slowDnsDoh = slowDnsDoh
                
                saveServer(uiState) 
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
                isEnabled = slowDnsEnabled,
                domain = slowDnsDomain,
                pubkey = slowDnsPubkey,
                doh = slowDnsDoh,
                onSave = { enabled, dom, key, dohUrl ->
                    slowDnsEnabled = enabled
                    slowDnsDomain = dom
                    slowDnsPubkey = key
                    slowDnsDoh = dohUrl
                    showSlowDnsDialog = false
                },
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

    // --- DIALOG SLOW DNS ---
    @Composable
    private fun SlowDnsDialog(
        isEnabled: Boolean,
        domain: String,
        pubkey: String,
        doh: String,
        onSave: (Boolean, String, String, String) -> Unit,
        onDismiss: () -> Unit
    ) {
        var tempEnabled by remember { mutableStateOf(isEnabled) }
        var tempDomain by remember { mutableStateOf(domain) }
        var tempPubkey by remember { mutableStateOf(pubkey) }
        var tempDoh by remember { mutableStateOf(doh) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Pengaturan SlowDNS (DNSTT)") },
            text = {
                Column {
                    Row {
                        Checkbox(
                            checked = tempEnabled,
                            onCheckedChange = { tempEnabled = it }
                        )
                        Text(
                            text = "Aktifkan Mode SlowDNS",
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    OutlinedTextField(
                        value = tempDomain,
                        onValueChange = { tempDomain = it },
                        label = { Text("NS Domain (contoh: ns.domain.com)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = tempPubkey,
                        onValueChange = { tempPubkey = it },
                        label = { Text("Public Key Server") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = tempDoh,
                        onValueChange = { tempDoh = it },
                        label = { Text("DoH URL (opsional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(tempEnabled, tempDomain, tempPubkey, tempDoh)
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
