#!/usr/bin/env python3
import pathlib
import shutil
import sys
source, output, *projects = sys.argv[1:]
for project in projects:
    root = pathlib.Path(source) / project
    for path in root.rglob('*'):
        if '.git' in path.parts or not path.is_file():
            continue
        if path.name.lower().startswith(('license', 'copying', 'copyright', 'notice', 'ftl.txt')):
            dest = pathlib.Path(output) / project / path.relative_to(root)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, dest)
