#!/usr/bin/env bash
# Rebrand v2rayNG -> NISA TUNNELING. Idempotent, jalankan dari root repo.
set -euo pipefail
APP_NAME="NISA TUNNELING"
APP_ID="id.nisavpn.tunneling"
APP="V2rayNG/app"

# Nama di launcher, header, notifikasi (semua bahasa)
sed -i "s#<string name=\"app_name\" translatable=\"false\">[^<]*</string>#<string name=\"app_name\" translatable=\"false\">${APP_NAME}</string>#" \
  "$APP"/src/main/res/values*/strings.xml
sed -i "s#v2rayNG service#${APP_NAME} service#" "$APP"/src/main/res/values/strings.xml

# applicationId (namespace tetap com.v2ray.ang supaya kode tidak perlu dipindah)
sed -i "s#applicationId = \"com.v2ray.ang\"#applicationId = \"${APP_ID}\"#" "$APP/build.gradle.kts"

# Nama file APK
sed -i "s#v2rayNG_#NISA-TUNNELING_#g" "$APP/build.gradle.kts"

grep -n 'name="app_name"' "$APP/src/main/res/values/strings.xml"
grep -n 'applicationId' "$APP/build.gradle.kts"
