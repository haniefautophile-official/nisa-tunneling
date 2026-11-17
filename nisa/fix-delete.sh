#!/usr/bin/env bash
# Profil yang sedang dipilih boleh dihapus (sebelumnya "Action not allowed"). Idempotent.
# Kalau VPN sedang jalan dan profil aktif dihapus, layanan dihentikan dulu.
set -euo pipefail
JAVA="V2rayNG/app/src/main/java/com/v2ray/ang"

python3 - "$JAVA" <<'PY'
import sys
java = sys.argv[1]
path = f"{java}/ui/main/MainViewModel.kt"
s = open(path, encoding="utf-8").read()
if "NISA_DELETE_SELECTED" in s:
    print("sudah dipatch:", path); sys.exit(0)

old = """        if (guid == uiState.value.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            dataSource.removeServer(guid)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
"""
new = """        // NISA_DELETE_SELECTED: profil aktif boleh dihapus; hentikan VPN dulu kalau sedang jalan
        if (guid == uiState.value.selectedGuid && uiState.value.isRunning) {
            com.v2ray.ang.core.LauncherManager.stopService(getApplication())
        }
        viewModelScope.launch(ioDispatcher) {
            dataSource.removeServer(guid)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
            refreshSelectedGuid()
        }
"""
assert s.count(old) == 1, "anchor removeServerAndRefresh tidak ditemukan/tidak unik"
open(path, "w", encoding="utf-8").write(s.replace(old, new))
print("ok:", path)
PY
echo "fix-delete selesai"
