package com.v2ray.ang.ui

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.util.NisaContact
import com.v2ray.ang.util.Utils

class NisaContactActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        NisaContactScreen(onBackClick = { finish() })
    }
}

@Composable
fun NisaContactScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val hasAny = NisaContact.waAdminUrl.isNotEmpty() ||
        NisaContact.telegramUrl.isNotEmpty() ||
        NisaContact.waGroupUrl.isNotEmpty()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_nisa_contact),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (!hasAny) {
                Text(
                    text = stringResource(R.string.nisa_contact_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (NisaContact.waAdminUrl.isNotEmpty()) {
                Button(
                    onClick = { Utils.openUri(context, NisaContact.waAdminUrl) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) { Text(stringResource(R.string.nisa_contact_wa_admin)) }
            }
            if (NisaContact.telegramUrl.isNotEmpty()) {
                Button(
                    onClick = { Utils.openUri(context, NisaContact.telegramUrl) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) { Text(stringResource(R.string.nisa_contact_telegram)) }
            }
            if (NisaContact.waGroupUrl.isNotEmpty()) {
                Button(
                    onClick = { Utils.openUri(context, NisaContact.waGroupUrl) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) { Text(stringResource(R.string.nisa_contact_wa_group)) }
            }
            NavigationBarsSpacer()
        }
    }
}
