#!/usr/bin/env bash
# Tab "Main | Log" di layar utama, banner server SSH tampil di kotak log,
# dan halaman Logcat ikut menampilkan log NISA. Idempotent. Jalankan setelah ui-cyber.sh & traffic-screen.sh.
set -euo pipefail
JAVA="V2rayNG/app/src/main/java/com/v2ray/ang"

python3 - "$JAVA" <<'PY'
import sys
java = sys.argv[1]

def patch(path, fn):
    s = open(path, encoding="utf-8").read()
    n = fn(s)
    open(path, "w", encoding="utf-8").write(n)
    print("ok:", path)

# ---------- 1) MainScreen: tab Main | Log ----------
def screen(s):
    if "NISA_LOG_TAB" in s:
        return s
    # state tab
    a = "    ModalNavigationDrawer(\n        drawerState = drawerState,"
    assert s.count(a) == 1, "anchor ModalNavigationDrawer tidak unik"
    s = s.replace(a, "    var nisaTab by rememberSaveable { mutableIntStateOf(0) } // NISA_LOG_TAB\n\n" + a)

    # tampilan Log (menggantikan daftar profil saat tab Log dipilih)
    b = "            Box(Modifier.fillMaxSize()) {\n            if (groups.isNotEmpty()) {\n"
    assert s.count(b) == 1, "anchor Box/groups tidak unik"
    log_view = (
        "            Box(Modifier.fillMaxSize()) {\n"
        "            if (nisaTab == 1) {\n"
        "                Column(\n"
        "                    modifier = Modifier\n"
        "                        .fillMaxSize()\n"
        "                        .padding(top = innerPadding.calculateTopPadding(), start = 8.dp, end = 8.dp)\n"
        "                ) {\n"
        "                    NisaMainTabs(nisaTab) { nisaTab = it }\n"
        "                    NisaLogPanel(\n"
        "                        Modifier\n"
        "                            .weight(1f)\n"
        "                            .fillMaxWidth()\n"
        "                            .padding(bottom = bottomBarHeight + 8.dp)\n"
        "                    )\n"
        "                }\n"
        "            } else if (groups.isNotEmpty()) {\n"
    )
    s = s.replace(b, log_view)

    # tab di atas daftar profil
    c = "                    if (groups.size > 1) {\n                        GroupTabBar("
    assert s.count(c) == 1, "anchor GroupTabBar tidak unik"
    s = s.replace(c, "                    NisaMainTabs(nisaTab) { nisaTab = it }\n" + c)
    return s
patch(f"{java}/ui/main/MainScreen.kt", screen)

# ---------- 2) SshManager: simpan banner server ----------
def ssh(s):
    if "ssh_banner.txt" in s:
        return s
    a = "            val launched = launch(ctx, profile, gen)\n"
    assert s.count(a) == 1, "anchor launch() di supervise tidak unik"
    s = s.replace(a, '            try { File(ctx.filesDir, "ssh_banner.txt").delete() } catch (_: Exception) { }\n' + a)

    b = '                            TunnelLog.add("SSH: " + line.removePrefix("[sshsocks] "))\n'
    assert s.count(b) == 1, "anchor TunnelLog.add di supervise tidak unik"
    extra = (
        '                            val rawLine = line.removePrefix("[sshsocks] ")\n'
        '                            if (rawLine.startsWith("banner: ")) {\n'
        '                                try { File(ctx.filesDir, "ssh_banner.txt").appendText(rawLine.removePrefix("banner: ") + "\\n") } catch (_: Exception) { }\n'
        '                            }\n'
    )
    return s.replace(b, extra + b)
patch(f"{java}/util/SshManager.kt", ssh)

# ---------- 3) Logcat: ikutkan tag NisaLog ----------
def logcat(s):
    if "NisaLog" in s:
        return s
    a = 'lst.add("GoLog,${AppConfig.ANG_PACKAGE},AndroidRuntime,System.err")'
    assert s.count(a) == 1, "anchor filter tag logcat tidak unik"
    return s.replace(a, 'lst.add("GoLog,NisaLog,${AppConfig.ANG_PACKAGE},AndroidRuntime,System.err")')
patch(f"{java}/ui/logcat/LogcatViewModel.kt", logcat)
PY
echo "log-tab selesai"
