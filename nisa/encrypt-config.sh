#!/usr/bin/env bash
# Ekspor/impor config terenkripsi (AES-256-GCM). Idempotent. Jalankan dari root repo, setelah branding.sh.
# Kunci diturunkan dari secret NISA_CONFIG_KEY (jika ada) atau APP_KEYSTORE_PASSWORD, lewat env NISA_SEED.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA="V2rayNG/app/src/main/java/com/v2ray/ang"

SEED="${NISA_SEED:-}"
if [ -z "$SEED" ]; then
  echo "::warning::NISA_SEED kosong (secret belum ada) - pakai seed sementara, config terenkripsi dari build ini tidak cocok dengan build lain"
  SEED="nisa-temporary-seed"
fi

python3 - "$JAVA" "$SEED" "$HERE" <<'PY'
import sys, os, secrets
java, seed, here = sys.argv[1], sys.argv[2].encode(), sys.argv[3]
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
open(f"{java}/util/NisaCrypt.kt", "w").write(open(f"{here}/NisaCrypt.kt").read())

p = f"{java}/handler/AngConfigManager.kt"
s = open(p, encoding="utf-8").read()
if "NisaCrypt" in s:
    print("sudah dipatch"); sys.exit(0)
def rep(old, new):
    global s
    assert s.count(old) == 1, "tidak unik/tidak ketemu: " + old
    s = s.replace(old, new)
rep("            Utils.setClipboard(context, conf)\n", "            Utils.setClipboard(context, com.v2ray.ang.util.NisaCrypt.wrap(conf))\n")
rep("                Utils.setClipboard(context, sb.toString())\n", "                Utils.setClipboard(context, com.v2ray.ang.util.NisaCrypt.wrap(sb.toString()))\n")
rep("                Utils.setClipboard(context, result.content)\n", "                Utils.setClipboard(context, com.v2ray.ang.util.NisaCrypt.wrap(result.content))\n")
rep("            return QRCodeDecoder.createQRCode(conf)\n", "            return QRCodeDecoder.createQRCode(com.v2ray.ang.util.NisaCrypt.wrap(conf))\n")
rep("    fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> {\n        return try {\n",
    "    fun importBatchConfig(serverIn: String?, subid: String, append: Boolean): Pair<Int, Int> {\n        val server = com.v2ray.ang.util.NisaCrypt.unwrap(serverIn)\n        return try {\n")
open(p, "w", encoding="utf-8").write(s)
print("patch enkripsi selesai")
PY
