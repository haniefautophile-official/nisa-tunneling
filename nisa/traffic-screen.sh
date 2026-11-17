#!/usr/bin/env bash
# Layar Trafik & Log terpisah + menu di drawer. Idempotent. Jalankan setelah branding.sh.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="V2rayNG/app/src/main"
JAVA="$APP/java/com/v2ray/ang"

cp "$HERE/NisaTrafficActivity.kt" "$JAVA/ui/NisaTrafficActivity.kt"

python3 - "$APP" "$JAVA" <<'PY'
import sys
app, java = sys.argv[1], sys.argv[2]
def patch(path, fn):
    s = open(path, encoding="utf-8").read(); n = fn(s)
    open(path, "w", encoding="utf-8").write(n)
    print("ok:", path)

def strings(s):
    if "title_nisa_traffic" in s: return s
    add = '    <string name="title_nisa_traffic" translatable="false">Trafik &amp; Log</string>\n'
    return s.replace("</resources>", add + "</resources>")
patch(f"{app}/res/values/strings.xml", strings)

def manifest(s):
    if "NisaTrafficActivity" in s: return s
    a = '        <activity\n            android:name=".ui.NisaContactActivity"'
    assert s.count(a) == 1, "NisaContactActivity tidak ada di manifest (jalankan branding.sh dulu)"
    return s.replace(a, '        <activity\n            android:name=".ui.NisaTrafficActivity"\n            android:exported="false" />\n' + a)
patch(f"{app}/AndroidManifest.xml", manifest)

def drawer(s):
    if "Traffic(" in s: return s
    e = "    Contact(R.drawable.ic_about_24dp, R.string.title_nisa_contact),\n"
    assert s.count(e) == 1, "entri Contact di enum drawer tidak ditemukan"
    s = s.replace(e, "    Traffic(R.drawable.ic_logcat_24dp, R.string.title_nisa_traffic),\n" + e)
    l = "    MainDestination.Contact,\n"
    assert s.count(l) == 1, "MainDestination.Contact di daftar drawer tidak ditemukan"
    return s.replace(l, "    MainDestination.Traffic,\n" + l)
patch(f"{java}/ui/main/MainDrawer.kt", drawer)

def main(s):
    if "NisaTrafficActivity" in s: return s
    a = "            MainDestination.Contact -> Intent(this, NisaContactActivity::class.java)\n"
    assert s.count(a) == 1, "routing Contact di MainActivity tidak ditemukan"
    s = s.replace(a, "            MainDestination.Traffic -> Intent(this, NisaTrafficActivity::class.java)\n" + a)
    i = "import com.v2ray.ang.ui.NisaContactActivity\n"
    assert s.count(i) == 1, "import NisaContactActivity tidak ditemukan"
    return s.replace(i, i + "import com.v2ray.ang.ui.NisaTrafficActivity\n")
patch(f"{java}/ui/main/MainActivity.kt", main)
PY
echo "traffic-screen selesai"
