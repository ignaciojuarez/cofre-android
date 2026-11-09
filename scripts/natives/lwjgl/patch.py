#!/usr/bin/env python3
"""Cofre's Android adaptations; independently written, not copied from builders."""
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
p = root / 'modules/lwjgl/core/src/generated/c/linux/org_lwjgl_system_linux_UIO.c'
s = p.read_text()
# These glibc wrappers are not exposed by bionic at API 28. Keep errno semantics.
s = s.replace('#include <sys/uio.h>\n#include <unistd.h>\n#include <sys/syscall.h>', '#include <sys/uio.h>')
s = s.replace('#include <sys/uio.h>', '#include <sys/uio.h>\n#include <unistd.h>\n#include <sys/syscall.h>')
for name in ['process_vm_readv', 'process_vm_writev']:
    s = s.replace(f'(jlong){name}(', f'(jlong)syscall(SYS_{name}, ')
p.write_text(s)
# 3.4.1 no longer includes X11 in core. zstd is not part of this build.

# Upstream hardcodes old Linux UAPI layouts; NDK already supplies these structs.
p = root / 'modules/lwjgl/core/src/main/c/linux/liburing/include/liburing/compat.h'
s = p.read_text()
start = s.find('struct __kernel_timespec {')
end = s.find('#ifndef BLOCK_URING_CMD_DISCARD')
if start >= 0:
    s = s[:start] + '#include <linux/time_types.h>\n#include <linux/openat2.h>\n\n' + s[end:]
p.write_text(s)
