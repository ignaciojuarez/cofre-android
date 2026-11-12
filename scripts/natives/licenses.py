#!/usr/bin/env python3
import pathlib
import shutil
import sys

# License tools that projects keep next to their licenses (shaderc's license_check.sh, license-checker.cfg).
NOT_TEXT = {'.sh', '.py', '.bat', '.cmd', '.ps1', '.cfg', '.ini', '.json', '.yml', '.yaml', '.toml', '.xml', '.cmake',
            '.gradle', '.kts', '.c', '.cc', '.cpp', '.h', '.hpp', '.java', '.js', '.go', '.rs', '.pl', '.rb', '.in'}


def is_license(name):
    return name.lower().startswith(('license', 'copying', 'copyright', 'notice', 'ftl.txt')) and \
        pathlib.PurePath(name).suffix.lower() not in NOT_TEXT


if __name__ == '__main__':
    source, output, *projects = sys.argv[1:]
    for project in projects:
        root = pathlib.Path(source) / project
        for path in root.rglob('*'):
            if '.git' in path.parts or not path.is_file():
                continue
            if is_license(path.name):
                dest = pathlib.Path(output) / project / path.relative_to(root)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, dest)
