#!/usr/bin/env bash
# Seed untuk membuka backup lama berformat "NISA1:". Ekspor/impor baru memakai password pengguna
# (lihat util/NisaCrypt.kt) sehingga tidak bergantung pada seed ini. Idempotent.
# Seed diambil dari env NISA_SEED (secret NISA_CONFIG_KEY atau APP_KEYSTORE_PASSWORD) agar backup lama tetap terbuka.
set -euo pipefail
JAVA="V2rayNG/app/src/main/java/com/v2ray/ang"

SEED="${NISA_SEED:-}"
if [ -z "$SEED" ]; then
  echo "::warning::NISA_SEED kosong - pakai seed bawaan; hanya backup NISA1: dari build tanpa secret yang bisa dibuka"
  SEED="nisa-temporary-seed"
fi

python3 - "$JAVA" "$SEED" <<'PY'
import sys, secrets
java, seed = sys.argv[1], sys.argv[2].encode()
mask = secrets.token_bytes(len(seed))
masked = bytes(a ^ b for a, b in zip(seed, mask))
fmt = lambda b: ", ".join(str(x) for x in b)
open(f"{java}/util/NisaSecret.kt", "w").write(f"""package com.v2ray.ang.util

internal object NisaSecret {{
    private val a = intArrayOf({fmt(masked)})
    private val b = intArrayOf({fmt(mask)})
    fun seed(): ByteArray = ByteArray(a.size) {{ (a[it] xor b[it]).toByte() }}
}}
""")
print("NisaSecret.kt dibuat")
PY
