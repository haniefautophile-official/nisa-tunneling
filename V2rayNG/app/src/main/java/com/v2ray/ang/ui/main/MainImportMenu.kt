package com.v2ray.ang.ui.main

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.compose.AppDropdownMenuItems
import com.v2ray.ang.ui.compose.InputDialog
import com.v2ray.ang.ui.compose.InputField
import com.v2ray.ang.ui.compose.SelectListDialog

private enum class ImportMenuAction(@StringRes val labelRes: Int, val action: MainAction) {
    QRCode(R.string.menu_item_import_config_qrcode, MainAction.ImportQRcode),
    Clipboard(R.string.menu_item_import_config_clipboard, MainAction.ImportClipboard),
    LocalFile(R.string.menu_item_import_config_local, MainAction.ImportConfigLocal),
    PolicyGroup(R.string.menu_item_import_config_policy_group, MainAction.ImportManually(EConfigType.POLICYGROUP.value)),
    ProxyChain(R.string.menu_item_import_config_proxy_chain, MainAction.ImportManually(EConfigType.PROXYCHAIN.value)),
    Vmess(R.string.menu_item_import_config_manually_vmess, MainAction.ImportManually(EConfigType.VMESS.value)),
    Vless(R.string.menu_item_import_config_manually_vless, MainAction.ImportManually(EConfigType.VLESS.value)),
    Shadowsocks(R.string.menu_item_import_config_manually_ss, MainAction.ImportManually(EConfigType.SHADOWSOCKS.value)),
    Socks(R.string.menu_item_import_config_manually_socks, MainAction.ImportManually(EConfigType.SOCKS.value)),
    Http(R.string.menu_item_import_config_manually_http, MainAction.ImportManually(EConfigType.HTTP.value)),
    Trojan(R.string.menu_item_import_config_manually_trojan, MainAction.ImportManually(EConfigType.TROJAN.value)),
    WireGuard(R.string.menu_item_import_config_manually_wireguard, MainAction.ImportManually(EConfigType.WIREGUARD.value)),
    Hysteria2(R.string.menu_item_import_config_manually_hysteria2, MainAction.ImportManually(EConfigType.HYSTERIA2.value)),
    Masque(R.string.menu_item_import_config_manually_masque, MainAction.ImportManually(EConfigType.MASQUE.value)),
    Ssh(R.string.menu_item_import_config_manually_ssh, MainAction.ImportManually(EConfigType.SSH.value))
}

enum class MainMoreMenuAction(@StringRes val labelRes: Int) {
    RestartService(R.string.title_service_restart),
    DeleteAll(R.string.title_del_all_config),
    DeleteDuplicate(R.string.title_del_duplicate_config),
    DeleteInvalid(R.string.title_del_invalid_config),
    LocateSelected(R.string.title_locate_selected_config),
    SortByTestResults(R.string.title_sort_by_test_results),
    TestAll(R.string.title_ping_all_server),
    TestAllRealPing(R.string.title_real_ping_all_server),
    UpdateSubscriptions(R.string.title_sub_update)
}

internal enum class ServerMenuAction(
    @StringRes val labelRes: Int,
    val isShareAction: Boolean,
    val supportsComplexProfiles: Boolean,
) {
    ShareLink(R.string.nisa_share_link, isShareAction = true, supportsComplexProfiles = false),
    ShareFile(R.string.nisa_share_file, isShareAction = true, supportsComplexProfiles = false),
    ShareQRCode(R.string.share_method_qrcode, isShareAction = true, supportsComplexProfiles = false),
    ShareFullContent(R.string.share_method_full_content, isShareAction = true, supportsComplexProfiles = true),
    Edit(R.string.action_edit, isShareAction = false, supportsComplexProfiles = true),
    Delete(R.string.action_delete, isShareAction = false, supportsComplexProfiles = true),
}

internal fun serverMenuActions(
    isComplexProfile: Boolean,
    includeManagementActions: Boolean,
): List<ServerMenuAction> = ServerMenuAction.entries.filter { action ->
    (includeManagementActions || action.isShareAction) && (!isComplexProfile || action.supportsComplexProfiles)
}

@Composable
fun ImportMenuContent(onAction: (MainAction) -> Unit) = AppDropdownMenuItems(
    items = ImportMenuAction.entries,
    labelRes = { it.labelRes },
    onSelected = { onAction(it.action) }
)

@Composable
fun MoreMenuContent(onSelected: (MainMoreMenuAction) -> Unit) = AppDropdownMenuItems(
    items = MainMoreMenuAction.entries,
    labelRes = { it.labelRes },
    onSelected = onSelected
)

@Composable
internal fun ShareMethodDialog(
    guid: String,
    profile: ProfileItem,
    more: Boolean,
    onDismiss: () -> Unit,
    onAction: (MainAction) -> Unit,
    onRemove: (String, String) -> Unit,
    onShare: (ShareKind) -> Unit,
) {
    val menuActions = serverMenuActions(
        isComplexProfile = profile.configType.isComplexType(),
        includeManagementActions = more,
    )
    SelectListDialog(
        options = menuActions,
        optionText = { stringResource(it.labelRes) },
        onSelected = { action ->
            onDismiss()
            when (action) {
                ServerMenuAction.ShareLink -> onShare(ShareKind.Link)
                ServerMenuAction.ShareFile -> onShare(ShareKind.File)
                ServerMenuAction.ShareQRCode -> onShare(ShareKind.QRCode)
                ServerMenuAction.ShareFullContent -> onAction(MainAction.ShareFullContent(guid))
                ServerMenuAction.Edit -> onAction(MainAction.EditServer(guid, profile))
                ServerMenuAction.Delete -> onRemove(guid, profile.remarks)
            }
        },
        onDismiss = onDismiss
    )
}

/** Cara membagikan akun; tiap cara lalu menanyakan standar (tanpa kunci) atau terenkripsi. */
internal enum class ShareKind { Link, File, QRCode }

internal fun shareAction(kind: ShareKind, guid: String, password: String?): MainAction = when (kind) {
    ShareKind.Link -> MainAction.ShareClipboard(guid, password)
    ShareKind.File -> MainAction.ShareFile(guid, password)
    ShareKind.QRCode -> MainAction.ShareQRCode(guid, password)
}

internal const val MIN_SHARE_PASSWORD = 4

internal fun isValidSharePassword(password: String, confirm: String): Boolean =
    password.length >= MIN_SHARE_PASSWORD && password == confirm

@Composable
internal fun ShareModeDialog(onPlain: () -> Unit, onEncrypted: () -> Unit, onDismiss: () -> Unit) {
    SelectListDialog(
        options = listOf(false, true),
        optionText = { stringResource(if (it) R.string.nisa_share_encrypted else R.string.nisa_share_plain) },
        title = stringResource(R.string.nisa_share_mode_title),
        onSelected = { encrypted -> if (encrypted) onEncrypted() else onPlain() },
        onDismiss = onDismiss
    )
}

@Composable
internal fun SharePasswordDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val invalidMessage = stringResource(R.string.nisa_password_invalid, MIN_SHARE_PASSWORD)
    InputDialog(
        title = stringResource(R.string.nisa_password_title),
        fields = listOf(
            InputField(stringResource(R.string.nisa_password_label), password, visualTransformation = PasswordVisualTransformation()),
            InputField(stringResource(R.string.nisa_password_confirm), confirm, visualTransformation = PasswordVisualTransformation()),
        ),
        onFieldChange = { index, value -> if (index == 0) password = value else confirm = value },
        confirmText = stringResource(R.string.action_ok),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { if (isValidSharePassword(password, confirm)) onConfirm(password) else context.toast(invalidMessage) },
        onDismiss = onDismiss
    )
}

@Composable
internal fun ImportPasswordDialog(isError: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    InputDialog(
        title = stringResource(R.string.nisa_import_password_title),
        fields = listOf(
            InputField(
                stringResource(if (isError) R.string.nisa_import_password_wrong else R.string.nisa_password_label),
                password,
                visualTransformation = PasswordVisualTransformation()
            )
        ),
        onFieldChange = { _, value -> password = value },
        confirmText = stringResource(R.string.action_ok),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { if (password.isNotEmpty()) onConfirm(password) },
        onDismiss = onDismiss
    )
}
