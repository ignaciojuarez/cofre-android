#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
[[ $# == 1 && $1 =~ ^natives-[0-9]{8}-[0-9]+$ ]] || { echo 'Usage: scripts/natives/fetch.sh natives-YYYYMMDD-N' >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST="$ROOT/.work/natives/$1"
mkdir -p "$DEST"
BASE="https://github.com/ignaciojuarez/cofre-android/releases/download/$1"
assets=(jre25-android-arm64.tar.xz lwjgl-3.4.1-android-arm64.tar.xz jna-5.17.0-android-arm64.tar.xz)
for asset in SHA256SUMS "${assets[@]}"; do curl -fL --retry 8 --retry-delay 5 --retry-all-errors -o "$DEST/$asset" "$BASE/$asset"; done
python3 - "$DEST" "${assets[@]}" <<'PY'
import hashlib,pathlib,sys
root=pathlib.Path(sys.argv[1])
checks={name:sha for sha,name in (line.split() for line in (root/'SHA256SUMS').read_text().splitlines())}
for name in sys.argv[2:]:
    actual=hashlib.sha256((root/name).read_bytes()).hexdigest()
    if checks.get(name) != actual:
        raise SystemExit(f'SHA-256 mismatch: {name}')
    print(f'{name}: OK')
PY
mkdir -p "$DEST/jre25"
tar -xJf "$DEST/jre25-android-arm64.tar.xz" -C "$DEST/jre25"
for asset in "${assets[@]:1}"; do tar -xJf "$DEST/$asset" -C "$DEST"; done
printf 'Verified natives: %s\n' "$DEST"
