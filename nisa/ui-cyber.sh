#!/usr/bin/env bash
# Tampilan NISA: tema cyber (neon, gelap), judul "NISA TUNNELING", kartu trafik + grafik
# upload/download di layar utama. Idempotent. Jalankan dari root repo, setelah branding.sh.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA="V2rayNG/app/src/main/java/com/v2ray/ang"

cp "$HERE/NisaTraffic.kt"     "$JAVA/util/NisaTraffic.kt"
cp "$HERE/NisaTrafficCard.kt" "$JAVA/ui/main/NisaTrafficCard.kt"

python3 - "$JAVA" <<'PY'
import sys, re
java = sys.argv[1]

def patch(path, fn):
    s = open(path, encoding="utf-8").read()
    n = fn(s)
    open(path, "w", encoding="utf-8").write(n)
    print("ok:", path)

def need(s, anchor, label):
    assert s.count(anchor) == 1, f"anchor '{label}' tidak ditemukan/tidak unik"

# ---------- 1) Warna tema: cyber neon, selalu gelap ----------
def theme(s):
    if "NISA_CYBER" in s:
        return s
    cyber = '''// NISA_CYBER
private val NisaCyberColor = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00262B),
    primaryContainer = Color(0xFF0B3A46),
    onPrimaryContainer = Color(0xFFB8F5FF),
    secondary = Color(0xFFFF2BD6),
    onSecondary = Color(0xFF3A0030),
    secondaryContainer = Color(0xFF55104A),
    onSecondaryContainer = Color(0xFFFFD6F6),
    tertiary = Color(0xFF39FF88),
    onTertiary = Color(0xFF00391A),
    tertiaryContainer = Color(0xFF0B4A29),
    onTertiaryContainer = Color(0xFFB8FFD3),
    error = Color(0xFFFF5C7A),
    errorContainer = Color(0xFF5C0A1E),
    onError = Color(0xFF3B0010),
    onErrorContainer = Color(0xFFFFD9E0),
    background = Color(0xFF05070F),
    onBackground = Color(0xFFE6F6FF),
    surface = Color(0xFF080D1A),
    onSurface = Color(0xFFE6F6FF),
    surfaceVariant = Color(0xFF14233D),
    onSurfaceVariant = Color(0xFF9DB6D4),
    outline = Color(0xFF2B6C86),
    outlineVariant = Color(0xFF16304A),
    inverseSurface = Color(0xFFE6F6FF),
    inverseOnSurface = Color(0xFF05070F),
    inversePrimary = Color(0xFF006A78),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF00E5FF),
    surfaceContainerLowest = Color(0xFF03050B),
    surfaceContainerLow = Color(0xFF070B16),
    surfaceContainer = Color(0xFF0B1224),
    surfaceContainerHigh = Color(0xFF101A33),
    surfaceContainerHighest = Color(0xFF16233F),
)

'''
    marker = "// Semantic Colors\n"
    need(s, marker, "Semantic Colors")
    s = s.replace(marker, cyber + marker)

    for old, new in [
        ("val colorConfigType = Color(0xFFf97910) // Orange", "val colorConfigType = Color(0xFFFF2BD6) // Neon pink"),
        ("val colorFabActive = Color(0xFFf97910) // Orange", "val colorFabActive = Color(0xFF00B8D4) // Neon cyan"),
        ("val colorFabInactiveDark = Color(0xFF646464) // Dark Gray", "val colorFabInactiveDark = Color(0xFF1B2A44) // Navy"),
        ("val dividerColorDark = Color(0xFF424242) // Dark Gray", "val dividerColorDark = Color(0xFF16304A) // Navy line"),
    ]:
        need(s, old, old)
        s = s.replace(old, new)

    # Selalu pakai palet cyber (tanpa warna dinamis / mode terang)
    m = re.search(r"    val colorScheme = when \{.*?        else -> LightColor\n    \}\n", s, re.S)
    assert m, "blok colorScheme tidak ditemukan"
    s = s.replace(m.group(0), "    val colorScheme = NisaCyberColor\n")

    head = "    content: @Composable () -> Unit\n) {\n"
    need(s, head, "AppTheme header")
    s = s.replace(head, head + "    @Suppress(\"NAME_SHADOWING\")\n    val darkTheme = true\n")
    return s
patch(f"{java}/ui/compose/Theme.kt", theme)

# ---------- 2) Judul layar utama ----------
def topbar(s):
    a = "        title = stringResource(R.string.title_server),\n"
    if a not in s:
        assert "NISA TUNNELING" in s, "judul tidak ditemukan"
        return s
    return s.replace(a, '        title = "NISA TUNNELING",\n')
patch(f"{java}/ui/main/MainTopBar.kt", topbar)

# ---------- 3) Kartu trafik di layar utama ----------
def screen(s):
    if "NisaTrafficCard" in s:
        return s
    a = "                ) {\n                    if (groups.size > 1) {\n"
    need(s, a, "Column daftar server")
    return s.replace(a, "                ) {\n                    NisaTrafficCard(isRunning = isRunning)\n                    if (groups.size > 1) {\n")
patch(f"{java}/ui/main/MainScreen.kt", screen)

# ---------- 4) Bilah koneksi bawah: tepi neon + glow ----------
def bottom(s):
    if "NISA_CYBER" in s:
        return s
    imp = "import androidx.compose.foundation.background\n"
    need(s, imp, "import background")
    s = s.replace(imp, imp + "import androidx.compose.foundation.border\n")
    sh = "                        .shadow(elevation = 3.dp, shape = CircleShape)\n"
    need(s, sh, "shadow bar")
    s = s.replace(sh, "                        .shadow(elevation = 10.dp, shape = CircleShape, ambientColor = Color(0xFF00E5FF), spotColor = Color(0xFF00E5FF)) // NISA_CYBER\n")
    bg = "                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)\n"
    need(s, bg, "background bar")
    s = s.replace(bg, bg + "                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f), CircleShape)\n")
    ta = "                            textAlign = TextAlign.Start,\n"
    need(s, ta, "textAlign status")
    return s.replace(ta, "                            color = Color(0xFFE6F6FF),\n" + ta)
patch(f"{java}/ui/main/MainBottomBar.kt", bottom)

# ---------- 6) Statistik Xray selalu menyala (penyebab grafik 0 B/s) ----------
def coremgr(s):
    if "NisaTraffic.SPEED_ON" in s:
        return s
    key = "MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED)"
    assert s.count(key) == 2, f"PREF_SPEED_ENABLED di CoreConfigManager: harapan 2, ketemu {s.count(key)}"
    s = s.replace(key, "NisaTraffic.SPEED_ON")
    imp = "import com.v2ray.ang.util.PackageUidResolver\n"
    need(s, imp, "import PackageUidResolver")
    return s.replace(imp, imp + "import com.v2ray.ang.util.NisaTraffic\n")
patch(f"{java}/core/CoreConfigManager.kt", coremgr)

# ---------- 5) Sumber data kecepatan: NotificationManager ----------
def notif(s):
    if "NisaTraffic" in s:
        return s
    gate = "        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) return\n"
    need(s, gate, "gate speed")
    s = s.replace(gate, "        // NISA: pemantau kecepatan selalu aktif supaya grafik di layar utama jalan\n")
    imp = "import com.v2ray.ang.util.LogUtil\n"
    need(s, imp, "import LogUtil")
    s = s.replace(imp, imp + "import com.v2ray.ang.util.NisaTraffic\n")
    z = "        val zeroSpeed = proxyTotal + directTotal == 0L\n"
    need(s, z, "zeroSpeed")
    return s.replace(z, z + "        NisaTraffic.publish(getService(), proxyUplink / sinceLastQueryInSeconds, proxyDownlink / sinceLastQueryInSeconds)\n")
patch(f"{java}/handler/NotificationManager.kt", notif)
PY
echo "ui-cyber selesai"
