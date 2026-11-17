#!/usr/bin/env bash
# Mengaktifkan enkripsi MMKV (config server, subscription, settings) dengan
# kunci yang dilindungi Android Keystore. Jalankan dari root repo, SETELAH
# rebrand.sh. Idempotent.
set -euo pipefail
APP="V2rayNG/app"
UTIL_DIR="$APP/src/main/java/com/v2ray/ang/util"
MMKV_FILE="$APP/src/main/java/com/v2ray/ang/handler/MmkvManager.kt"

# 1) Salin KeyProvider.kt
cp "$(dirname "$0")/KeyProvider.kt" "$UTIL_DIR/KeyProvider.kt"

# 2) Tambah import (kalau belum ada)
if ! grep -q "import com.v2ray.ang.util.KeyProvider" "$MMKV_FILE"; then
  sed -i "0,/^import /s//import com.v2ray.ang.util.KeyProvider\nimport /" "$MMKV_FILE"
fi

# 3) Tambah cryptKey ke tiap mmkvWithID(..., MMKV.MULTI_PROCESS_MODE)
sed -i -E 's/MMKV\.mmkvWithID\(([A-Z_]+), MMKV\.MULTI_PROCESS_MODE\)/MMKV.mmkvWithID(\1, MMKV.MULTI_PROCESS_MODE, KeyProvider.getMmkvCryptKey())/' "$MMKV_FILE"

echo "--- hasil patch ---"
grep -n "mmkvWithID\|import com.v2ray.ang.util.KeyProvider" "$MMKV_FILE"
