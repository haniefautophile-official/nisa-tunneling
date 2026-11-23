package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField

/**
 * Semua tipe record yang didukung klien dnstt (libdnstt.so). "Otomatis" = TXT (standar dnstt).
 * Tipe di sini HARUS sama dengan tipe yang dilayani server SlowDNS.
 */
val SLOWDNS_TYPE_OPTIONS = listOf("Otomatis", "TXT", "NULL", "CNAME", "MX", "NS", "SRV", "A", "AAAA", "CAA")

/** Status form SlowDNS yang dipakai bersama oleh profil Trojan, VMess, dan VLESS. */
class SlowDnsFormState(
    enabled: Boolean,
    type: String,
    domain: String,
    pubkey: String,
    doh: String,
) {
    var enabled by mutableStateOf(enabled)
    var type by mutableStateOf(type)
    var domain by mutableStateOf(domain)
    var pubkey by mutableStateOf(pubkey)
    var doh by mutableStateOf(doh)

    /** Kalau SlowDNS aktif, NS Domain dan Public Key wajib diisi. */
    fun isValid(): Boolean = !enabled || (domain.isNotBlank() && pubkey.isNotBlank())

    /** Tulis isi form ke profil sebelum disimpan. */
    fun applyTo(c: ProfileItem) {
        c.slowDnsEnabled = enabled
        c.slowDnsDomain = domain.trim()
        c.slowDnsPubkey = pubkey.trim()
        c.slowDnsDoh = doh.trim()
        c.slowDnsType = type.trim().ifEmpty { null }
    }

    companion object {
        fun from(c: ProfileItem) = SlowDnsFormState(
            enabled = c.slowDnsEnabled,
            type = c.slowDnsType?.takeIf { it.isNotBlank() } ?: "Otomatis",
            domain = c.slowDnsDomain ?: "",
            pubkey = c.slowDnsPubkey ?: "",
            doh = c.slowDnsDoh ?: "",
        )

        val Saver = listSaver<SlowDnsFormState, Any>(
            save = { listOf(it.enabled, it.type, it.domain, it.pubkey, it.doh) },
            restore = {
                SlowDnsFormState(
                    enabled = it[0] as Boolean,
                    type = it[1] as String,
                    domain = it[2] as String,
                    pubkey = it[3] as String,
                    doh = it[4] as String,
                )
            }
        )
    }
}

@Composable
fun rememberSlowDnsFormState(initial: ProfileItem): SlowDnsFormState =
    rememberSaveable(saver = SlowDnsFormState.Saver) { SlowDnsFormState.from(initial) }

/** Form SlowDNS satu halaman (sama seperti di profil SSH): centang, Type, NS Domain, Public Key, DNS. */
@Composable
fun SlowDnsFields(state: SlowDnsFormState) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = state.enabled, onCheckedChange = { state.enabled = it })
        Text("Aktifkan SlowDNS (DNSTT)")
    }
    if (state.enabled) {
        FormDropdownField(
            "Type",
            state.type,
            SLOWDNS_TYPE_OPTIONS,
            { state.type = it },
            supportingText = "Harus sama dengan tipe record di server"
        )
        FormTextField(
            label = "NS Domain (contoh: ns.domain.com)",
            value = state.domain,
            onValueChange = { state.domain = it }
        )
        FormTextField(
            label = "Public Key Server",
            value = state.pubkey,
            onValueChange = { state.pubkey = it }
        )
        FormTextField(
            label = "DNS (kosong = DNS operator; host:port UDP; https:// untuk DoH)",
            value = state.doh,
            onValueChange = { state.doh = it }
        )
    }
}
