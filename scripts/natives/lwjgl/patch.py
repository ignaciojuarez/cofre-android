#!/usr/bin/env python3
"""Cofre's Android adaptations; independently written, not copied from builders."""
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
p = root / 'modules/lwjgl/core/src/generated/c/linux/org_lwjgl_system_linux_UIO.c'
s = p.read_text()
# These glibc wrappers are not exposed by bionic at API 28. Keep errno semantics.
s = s.replace('#include <sys/uio.h>', '#include <sys/uio.h>\n#include <unistd.h>\n#include <sys/syscall.h>')
for name in ['process_vm_readv', 'process_vm_writev']:
    s = s.replace(f'(jlong){name}(', f'(jlong)syscall(SYS_{name}, ')
p.write_text(s)
# 3.4.1 no longer includes X11 in core. zstd is not part of this build.
