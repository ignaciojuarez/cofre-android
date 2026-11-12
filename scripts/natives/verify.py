#!/usr/bin/env python3
"""Fail closed on empty, non-aarch64, non-ELF or <16 KiB LOAD-aligned inputs, on libraries
Android doesn't provide and the piece doesn't ship, and on libc symbols newer than API 28."""
import hashlib
import pathlib
import re
import subprocess
import sys

# The NDK's stable system libraries. Anything else must ship in the same piece.
SYSTEM_LIBS = {
    'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libz.so', 'libandroid.so', 'libjnigraphics.so',
    'libEGL.so', 'libGLESv2.so', 'libGLESv3.so', 'libvulkan.so', 'libnativewindow.so',
    'libOpenSLES.so', 'libaaudio.so', 'libmediandk.so',
}


def load_alignments(output):
    values = [int(line.split()[-1], 16) for line in output.splitlines()
              if line.split()[:1] == ['LOAD']]
    if not values or min(values) < 0x4000:
        raise ValueError(f'LOAD alignment below 0x4000 or missing: {values}')
    return values


def check_needed(output, own):
    """`readelf -d` output: every DT_NEEDED is a system library or one of [own] (glibc's libc.so.6, libc++_shared aren't)."""
    needed = re.findall(r'\(NEEDED\)\s+Shared library: \[([^\]]+)\]', output)
    unexpected = [name for name in needed if name not in SYSTEM_LIBS and name not in own]
    if unexpected:
        raise ValueError(f'Unpackaged or non-Android dependency: {unexpected}')
    return needed


def check_libc_versions(output):
    """`readelf --dyn-syms -W` output: no strong undefined symbol from bionic past API 28 (LIBC_P), which would fail to load."""
    newer = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 8 or fields[6] != 'UND' or fields[4] == 'WEAK':
            continue
        version = re.search(r'@LIBC_([A-Z0-9]+)', fields[7])
        if version and (len(version.group(1)) > 1 or version.group(1) > 'P'):
            newer.append(fields[7])
    if newer:
        raise ValueError(f'libc symbols newer than API 28: {newer}')


def main():
    readelf, directory = sys.argv[1:]
    root = pathlib.Path(directory)
    files = sorted(p for p in root.rglob('*') if p.is_file() and '.so' in p.name)
    java = root / 'bin/java'
    if java.exists():
        files.append(java)
        if 'JAVA_VERSION="25.' not in (root / 'release').read_text():
            raise ValueError('Expected Java 25 release metadata')
        for required in ['lib/server/libjvm.so', 'lib/modules', 'conf', 'legal']:
            if not (root / required).exists():
                raise ValueError(f'Incomplete JRE: {required}')
    if not files:
        raise ValueError('No native libraries found')
    own = {p.name for p in files}
    print('file\tmin_LOAD_align\tsha256')
    for path in files:
        header = subprocess.check_output([readelf, '-h', str(path)], text=True)
        if 'AArch64' not in header:
            raise ValueError(f'Not aarch64: {path}')
        try:
            check_needed(subprocess.check_output([readelf, '-d', str(path)], text=True), own)
            check_libc_versions(subprocess.check_output([readelf, '--dyn-syms', '-W', str(path)], text=True))
        except ValueError as e:
            raise ValueError(f'{path}: {e}') from None
        segments = subprocess.check_output([readelf, '-lW', str(path)], text=True)
        align = min(load_alignments(segments))
        print(f'{path.relative_to(root)}\t0x{align:x}\t{hashlib.sha256(path.read_bytes()).hexdigest()}')


if __name__ == '__main__':
    main()
