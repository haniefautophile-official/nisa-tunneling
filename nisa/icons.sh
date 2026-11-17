#!/usr/bin/env bash
# Ganti ikon V jadi N: launcher (mipmap-*) dan ikon status bar (ic_stat_name*). Idempotent.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RES="V2rayNG/app/src/main/res"
for d in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  cp "$HERE/assets/icons/mipmap-$d/"*.png "$RES/mipmap-$d/"
  cp "$HERE/assets/icons/drawable-$d/ic_stat_name.png" "$RES/drawable-$d/ic_stat_name.png"
  [ -f "$RES/drawable-$d/ic_stat_name_black.png" ] && cp "$HERE/assets/icons/drawable-$d/ic_stat_name_black.png" "$RES/drawable-$d/ic_stat_name_black.png"
done
echo "icons selesai"
