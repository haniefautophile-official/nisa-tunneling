package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.ui.compose.verticalScrollbar

/** Dialog "versi baru tersedia" yang muncul otomatis saat aplikasi dibuka. */
@Composable
fun UpdateAvailableDialog(
    result: CheckUpdateResult,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_new_version_found, result.latestVersion ?: "")) },
        text = {
            val scrollState = rememberScrollState()
            Text(
                text = result.releaseNotes.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .verticalScrollbar(scrollState)
            )
        },
        confirmButton = {
            TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_now)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
