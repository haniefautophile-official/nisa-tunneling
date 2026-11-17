#!/usr/bin/env bash
# Menu "Hubungi Admin" + wallpaper NISA. Idempotent. Jalankan dari root repo, setelah rebrand.sh.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="V2rayNG/app/src/main"
JAVA="$APP/java/com/v2ray/ang"

# 1) Baca kontak, buat NisaContact.kt
get() { grep -E "^$1=" "$HERE/contact.properties" | head -1 | cut -d= -f2- | tr -d '\r' | xargs || true; }
WA="$(get WA_ADMIN | tr -cd '0-9')"
TG="$(get TELEGRAM_URL)"; WG="$(get WA_GROUP_URL)"
case "$TG" in ""|https://*) ;; *) echo "TELEGRAM_URL harus diawali https://"; exit 1;; esac
case "$WG" in ""|https://*) ;; *) echo "WA_GROUP_URL harus diawali https://"; exit 1;; esac
WA_URL=""; [ -n "$WA" ] && WA_URL="https://wa.me/$WA"
cat > "$JAVA/util/NisaContact.kt" <<KT
package com.v2ray.ang.util

object NisaContact {
    const val waAdminUrl = "$WA_URL"
    const val telegramUrl = "$TG"
    const val waGroupUrl = "$WG"
}
KT

# 2) Activity kontak
cp "$HERE/NisaContactActivity.kt" "$JAVA/ui/NisaContactActivity.kt"

# 3) Wallpaper (window background, tampil di balik layar utama)
mkdir -p "$APP/res/drawable-nodpi"
cp "$HERE/assets/wallpaper.jpg" "$APP/res/drawable-nodpi/nisa_wallpaper.jpg"
cat > "$APP/res/drawable/nisa_window_bg.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@android:color/black" />
    <item>
        <bitmap
            android:src="@drawable/nisa_wallpaper"
            android:gravity="center" />
    </item>
</layer-list>
XML
sed -i 's#@color/window_background_light#@drawable/nisa_window_bg#; s#@color/window_background_dark#@drawable/nisa_window_bg#' \
  "$APP/res/values/themes.xml" "$APP/res/values-night/themes.xml"

python3 - "$APP" "$JAVA" <<'PY'
import sys, re
app, java = sys.argv[1], sys.argv[2]
def patch(path, fn):
    s = open(path, encoding="utf-8").read(); n = fn(s)
    if n == s: print("sudah/tidak berubah:", path)
    open(path, "w", encoding="utf-8").write(n)

# Strings
def strings(s):
    if "title_nisa_contact" in s: return s
    add = '''    <string name="title_nisa_contact" translatable="false">Hubungi Admin</string>
    <string name="nisa_contact_wa_admin" translatable="false">WhatsApp Admin</string>
    <string name="nisa_contact_telegram" translatable="false">Grup Telegram</string>
    <string name="nisa_contact_wa_group" translatable="false">Grup WhatsApp</string>
    <string name="nisa_contact_empty" translatable="false">Kontak admin belum diatur.</string>
'''
    return s.replace("</resources>", add + "</resources>")
patch(f"{app}/res/values/strings.xml", strings)

# Manifest
def manifest(s):
    if "NisaContactActivity" in s: return s
    a = '        <activity\n            android:name=".ui.AboutActivity"'
    assert a in s, "AboutActivity tidak ditemukan di manifest"
    return s.replace(a, '        <activity\n            android:name=".ui.NisaContactActivity"\n            android:exported="false" />\n' + a)
patch(f"{app}/AndroidManifest.xml", manifest)

# Drawer
def drawer(s):
    if "Contact(" in s: return s
    e = "    About(R.drawable.ic_about_24dp, R.string.title_about)"
    assert e in s
    s = s.replace(e, "    Contact(R.drawable.ic_about_24dp, R.string.title_nisa_contact),\n" + e)
    l = "    MainDestination.BackupRestore,\n"
    assert l in s
    return s.replace(l, l + "    MainDestination.Contact,\n")
patch(f"{java}/ui/main/MainDrawer.kt", drawer)

# MainActivity
def main(s):
    if "NisaContactActivity" in s: return s
    a = "            MainDestination.About -> Intent(this, AboutActivity::class.java)\n"
    assert a in s
    s = s.replace(a, "            MainDestination.Contact -> Intent(this, NisaContactActivity::class.java)\n" + a)
    return s.replace("import com.v2ray.ang.ui.AboutActivity\n",
                     "import com.v2ray.ang.ui.AboutActivity\nimport com.v2ray.ang.ui.NisaContactActivity\n")
patch(f"{java}/ui/main/MainActivity.kt", main)

# MainScreen: Scaffold transparan supaya wallpaper terlihat
def screen(s):
    if "NISA_WALLPAPER" in s: return s
    a = "            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,\n"
    assert s.count(a) == 1, "Scaffold utama tidak unik"
    return s.replace(a, a + "            containerColor = androidx.compose.ui.graphics.Color.Transparent, // NISA_WALLPAPER\n")
patch(f"{java}/ui/main/MainScreen.kt", screen)
PY
bash "$HERE/ui-cyber.sh"
echo "branding selesai"
