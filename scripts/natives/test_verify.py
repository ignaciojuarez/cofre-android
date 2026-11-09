#!/usr/bin/env python3
from verify import load_alignments

assert load_alignments('  LOAD 0x00 0x00 0x00 0x10 0x10 R E 0x4000') == [16384]
for bad in ['', ' LOAD 0x00 R 0x1000', ' LOAD 0x00 R 0x4000\n LOAD 0x00 RW 0x1000']:
    try:
        load_alignments(bad)
    except ValueError:
        pass
    else:
        raise AssertionError(f'Accepted invalid ELF segments: {bad}')
print('Alignment gate: positive and negative checks passed')
