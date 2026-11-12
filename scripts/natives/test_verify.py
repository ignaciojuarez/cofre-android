#!/usr/bin/env python3
import pathlib
import sys
sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # also under python3 -I
from verify import check_libc_versions, check_needed, load_alignments


def rejects(check, *args):
    try:
        check(*args)
    except ValueError:
        pass
    else:
        raise AssertionError(f'Accepted invalid input: {args}')


assert load_alignments('  LOAD 0x00 0x00 0x00 0x10 0x10 R E 0x4000') == [16384]
for bad in ['', ' LOAD 0x00 R 0x1000', ' LOAD 0x00 R 0x4000\n LOAD 0x00 RW 0x1000']:
    rejects(load_alignments, bad)

dynamic = ''' 0x0000000000000001 (NEEDED)             Shared library: [{}]
 0x0000000000000001 (NEEDED)             Shared library: [libc.so]'''
assert check_needed(dynamic.format('libjvm.so'), {'libjvm.so', 'libjava.so'}) == ['libjvm.so', 'libc.so']
assert check_needed(dynamic.format('libOpenSLES.so'), set()) == ['libOpenSLES.so', 'libc.so']
for bad in ['libc.so.6', 'libc++_shared.so', 'libpthread.so.0', 'libjvm.so']:
    rejects(check_needed, dynamic.format(bad), {'libjava.so'})

symbols = '''Symbol table '.dynsym' contains 4 entries:
   Num:    Value          Size Type    Bind   Vis       Ndx Name
     0: 0000000000000000     0 NOTYPE  LOCAL  DEFAULT   UND
     1: 0000000000000000     0 FUNC    GLOBAL DEFAULT   UND __cxa_atexit@LIBC
     2: 0000000000000000     0 FUNC    GLOBAL DEFAULT   UND pthread_cond_clockwait@LIBC_{}
     3: 0000000000000000     0 FUNC    WEAK   DEFAULT   UND getrandom@LIBC_Q
     4: 0000000000001234    16 FUNC    GLOBAL DEFAULT    12 mine@@LIBC_R'''
for ok in ['N', 'O', 'P']:
    check_libc_versions(symbols.format(ok))
for bad in ['Q', 'R', 'V', 'PRIVATE']:
    rejects(check_libc_versions, symbols.format(bad))
print('Natives gate: alignment, needed-library and libc-version checks passed')
