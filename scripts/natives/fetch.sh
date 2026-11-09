#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
[[ $# == 1 && $1 =~ ^natives-[0-9]{8}-[0-9]+$ ]] || { echo 'Usage: scripts/natives/fetch.sh natives-YYYYMMDD-N' >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST="$ROOT/.work/natives/$1"
mkdir -p "$DEST"
BASE="https://github.com/ignaciojuarez/cofre-android/releases/download/$1"
assets=(jre25-android-arm64.tar.xz lwjgl-3.4.1-android-arm64.tar.xz jna-5.17.0-android-arm64.tar.xz)
download() { curl -fL --retry 8 --retry-delay 5 --retry-all-errors -o "$DEST/$1" "$BASE/$1"; }
sha256() { python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"; }

# SHA256SUMS comes from the same release as the files it checks, so it is only trusted when its own
# hash matches the one committed in gradle.properties (cofre.natives.sums) for this tag.
download SHA256SUMS
sums=$(sha256 "$DEST/SHA256SUMS")
pinned_tag=$(sed -n 's/^cofre\.natives=//p' "$ROOT/gradle.properties")
pinned_sums=$(sed -n 's/^cofre\.natives\.sums=//p' "$ROOT/gradle.properties")
if [[ $1 != "$pinned_tag" || $sums != "$pinned_sums" ]]; then
  echo "SHA256SUMS of $1 is $sums, which isn't pinned in gradle.properties ($pinned_tag: $pinned_sums)." >&2
  echo 'For a new release, check that hash against the release run, then set cofre.natives and cofre.natives.sums.' >&2
  exit 1
fi

for asset in "${assets[@]}"; do download "$asset"; done
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
