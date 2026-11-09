#!/usr/bin/env python3
"""Fail closed on empty, non-aarch64, non-ELF or <16 KiB LOAD-aligned inputs."""
import hashlib
import pathlib
import subprocess
import sys


def load_alignments(output):
    values = [int(line.split()[-1], 16) for line in output.splitlines()
              if line.split()[:1] == ['LOAD']]
    if not values or min(values) < 0x4000:
        raise ValueError(f'LOAD alignment below 0x4000 or missing: {values}')
    return values


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
    print('file\tmin_LOAD_align\tsha256')
    for path in files:
        header = subprocess.check_output([readelf, '-h', str(path)], text=True)
        if 'AArch64' not in header:
            raise ValueError(f'Not aarch64: {path}')
        dynamic = subprocess.check_output([readelf, '-d', str(path)], text=True)
        if any(name in dynamic for name in ['libc.so.6', 'libstdc++.so.6', 'libpthread.so.0', 'libc++_shared.so']):
            raise ValueError(f'Unpackaged or glibc runtime dependency: {path}')
        segments = subprocess.check_output([readelf, '-lW', str(path)], text=True)
        align = min(load_alignments(segments))
        print(f'{path.relative_to(root)}\t0x{align:x}\t{hashlib.sha256(path.read_bytes()).hexdigest()}')


if __name__ == '__main__':
    main()
