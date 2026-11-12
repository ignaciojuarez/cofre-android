#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Build, verify and publish signed Cofre releases. Installed apps see a release only once the update
feed (updates/latest.json on main) is pushed, and publish pushes it last. See docs/updates.md.

  setup                                                        once: release keystore + Keychain password
  build --version 0.1.0-alpha.1 --code 2 --notes FILE --output DIR    signed, verified APK; publishes nothing
  publish DIR                                                  tag, GitHub release, public check, then the feed
"""
import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = 'ignaciojuarez/cofre-android'
PACKAGE = 'com.ignacio.cofre'
FEED = 'updates/latest.json'
FEED_URL = f'https://raw.githubusercontent.com/{REPO}/main/{FEED}'
CERTIFICATE = ROOT / 'updates/signing-certificate.txt'
KEYSTORE = Path.home() / 'Library/Application Support/Cofre/release/cofre-release.jks'
KEYCHAIN = ['-s', 'cofre-android-release', '-a', 'keystore']
ALIAS = 'cofre'
PASSWORD = 'COFRE_KEYSTORE_PASSWORD'
BUILT_APK = ROOT / 'app/build/outputs/apk/release/app-release.apk'
REQUIRED = ['lib/arm64-v8a/libcofre.so', 'lib/arm64-v8a/libcofrevk.so', 'lib/arm64-v8a/liblwjgl.so',
            'assets/runtime/jre-25.tar.xz']
# The updater's contract (core/kit AppRelease): exactly these keys and types.
FEED_KEYS = {'versionCode': int, 'versionName': str, 'tag': str, 'apk': str, 'size': int, 'sha256': str,
             'minSdk': int, 'notes': str}
VERSION = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?')
SHA256 = re.compile(r'[0-9a-f]{64}')


# Pure parts (scripts/test_release.py)

def tag_for(version):
    if not VERSION.fullmatch(version):
        raise ValueError(f'Use a version such as 0.1.0 or 0.1.0-alpha.1, not {version!r}')
    return 'v' + version


def apk_name(tag):
    return f'Cofre-{tag}.apk'


def apk_url(tag):
    return f'https://github.com/{REPO}/releases/download/{tag}/{apk_name(tag)}'


def check_feed(feed):
    if not isinstance(feed, dict) or set(feed) != set(FEED_KEYS):
        raise ValueError(f'The feed needs exactly these keys: {", ".join(FEED_KEYS)}')
    for key, kind in FEED_KEYS.items():
        if type(feed[key]) is not kind:  # also refuses true/false as numbers
            raise ValueError(f'Feed {key} must be a {kind.__name__}')
    tag = tag_for(feed['versionName'])
    if min(feed['versionCode'], feed['size'], feed['minSdk']) < 1:
        raise ValueError('Feed versionCode, size and minSdk must be positive')
    if (feed['tag'], feed['apk']) != (tag, apk_url(tag)):
        raise ValueError(f'Feed tag and apk must be {tag} and {apk_url(tag)}')
    if not SHA256.fullmatch(feed['sha256']):
        raise ValueError('Feed sha256 must be 64 lowercase hex digits')
    return feed


def make_feed(version, code, size, sha256, min_sdk, notes):
    tag = tag_for(version)
    return check_feed({'versionCode': code, 'versionName': version, 'tag': tag, 'apk': apk_url(tag),
                       'size': size, 'sha256': sha256, 'minSdk': min_sdk, 'notes': notes.strip()})


def feed_json(feed):
    return json.dumps(check_feed(feed), indent=2, ensure_ascii=False) + '\n'


def check_newer(code, public):
    """`public` is the feed installed apps read today, or None before the first release."""
    if code < 1:
        raise ValueError('--code must be a positive integer')
    if public is not None and code <= check_feed(public)['versionCode']:
        raise ValueError(f'--code must be greater than {public["versionCode"]}, the published {public["versionName"]}')


def sums_line(name, sha256):
    return f'{sha256}  {name}\n'


def signers(apksigner_output):
    """Distinct signer certificate SHA-256s in `apksigner verify --print-certs` output. Newer build-tools print
    a line per scheme ("V2 Signer: certificate ...", "V3 Signer: ..."), older ones "Signer #1 certificate ..."."""
    found = re.findall(r'^(?:Signer #\d+|V[\d.]+ Signer(?: #\d+)?:) certificate SHA-256 digest: ([0-9a-f]{64})$',
                       apksigner_output, re.M)
    return list(dict.fromkeys(found))


def badging(aapt2_output):
    """(package, versionCode, versionName, minSdk) from `aapt2 dump badging`."""
    package = re.search(r"^package: name='([^']*)' versionCode='(\d+)' versionName='([^']*)'", aapt2_output, re.M)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'$", aapt2_output, re.M)
    if not package or not sdk:
        raise ValueError("Couldn't read the APK's package, version and minSdk")
    return package[1], int(package[2]), package[3], int(sdk[1])


def certificate_text(sha256):
    return f'# SHA-256 of the certificate that signs Cofre releases. Installed apps only accept updates signed by it.\n{sha256}\n'


def read_certificate(text):
    lines = [line.strip() for line in text.splitlines() if line.strip() and not line.startswith('#')]
    if len(lines) != 1 or not SHA256.fullmatch(lines[0]):
        raise ValueError('The certificate file needs one SHA-256: 64 lowercase hex digits, no colons')
    return lines[0]


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


# Tools

def run(*args, capture=False, env=None):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True, text=True, env=env,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def succeeds(*args):
    return subprocess.run([str(arg) for arg in args], cwd=ROOT, capture_output=True).returncode == 0


def git(*args):
    return run('git', *args, capture=True)


def java_home():
    """The JDK scripts/gradle.sh builds with."""
    default = re.search(r'JAVA_HOME_OVERRIDE:-([^}]+)}', (ROOT / 'scripts/gradle.sh').read_text())[1]
    return Path(os.environ.get('JAVA_HOME_OVERRIDE') or default)


def tool_env(**extra):
    home = java_home()
    return {**os.environ, 'JAVA_HOME': str(home), 'PATH': f'{home}/bin:{os.environ["PATH"]}', **extra}


def build_tools():
    sdk = Path(os.environ.get('ANDROID_HOME') or Path.home() / 'Library/Android/sdk') / 'build-tools'
    found = [path for path in sdk.glob('*') if (path / 'apksigner').is_file()]
    if not found:
        raise ValueError(f'No Android build-tools in {sdk}')
    return max(found, key=lambda path: [int(number) for number in re.findall(r'\d+', path.name)])


def keychain_password():
    result = subprocess.run(['security', 'find-generic-password', *KEYCHAIN, '-w'], capture_output=True, text=True)
    return result.stdout.rstrip('\n') if result.returncode == 0 else None


def keystore_password():
    password = os.environ.get(PASSWORD) or keychain_password()
    if not password:
        raise ValueError('No release key password in the Keychain: run `python3 scripts/release.py setup` once')
    return password


def keystore_certificate(keystore, password):
    """SHA-256 of the release certificate in `keystore`, as apksigner prints it."""
    der = subprocess.run([str(java_home() / 'bin/keytool'), '-exportcert', '-alias', ALIAS, '-keystore', str(keystore),
                          '-storepass:env', PASSWORD], env=tool_env(**{PASSWORD: password}),
                         check=True, capture_output=True).stdout
    return hashlib.sha256(der).hexdigest()


def source_revision(allow_dirty=False):
    """HEAD, which must be a clean origin/main unless this is a local dry run."""
    git('fetch', '--quiet', 'origin', 'main')
    head, main = git('rev-parse', 'HEAD'), git('rev-parse', 'origin/main')
    if not allow_dirty and git('status', '--porcelain'):
        raise ValueError('Releases come from a clean checkout: commit or set aside your changes first')
    if not allow_dirty and head != main:
        raise ValueError(f'Releases come from origin/main ({main[:12]}), but HEAD is {head[:12]}')
    return head


def public_feed():
    """The feed installed apps read now, or None before the first release (404)."""
    request = urllib.request.Request(FEED_URL, headers={'Cache-Control': 'no-cache'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return check_feed(json.load(response))
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def pinned_natives():
    tag = re.search(r'^cofre\.natives=(\S+)$', (ROOT / 'gradle.properties').read_text(), re.M)[1]
    natives = ROOT / '.work/natives' / tag
    if not (natives / 'lib').is_dir() or not (natives / 'jre25-android-arm64.tar.xz').is_file():
        raise ValueError(f'The pinned natives are missing: run scripts/natives/fetch.sh {tag}')


def verify_apk(apk, code, version, certificate):
    """Signed by the release key, 16 KB-aligned, the right package and version, natives inside. Returns minSdk."""
    tools = build_tools()
    found = signers(run(tools / 'apksigner', 'verify', '--print-certs', apk, capture=True, env=tool_env()))
    if found != [certificate]:
        raise ValueError(f'{apk.name} is signed by {found or "nobody"}, not the release certificate {certificate}')
    run(tools / 'zipalign', '-c', '-P', '16', '4', apk)
    package, apk_code, apk_version, min_sdk = badging(run(tools / 'aapt2', 'dump', 'badging', apk, capture=True))
    if (package, apk_code, apk_version) != (PACKAGE, code, version):
        raise ValueError(f'{apk.name} is {package} {apk_version} ({apk_code}), not {PACKAGE} {version} ({code})')
    with zipfile.ZipFile(apk) as archive:
        missing = sorted(set(REQUIRED) - set(archive.namelist()))
    if missing:
        raise ValueError(f'{apk.name} lacks {", ".join(missing)}')
    return min_sdk


# Commands

def setup(args):
    keystore, certificate = args.keystore.expanduser().resolve(), args.certificate.resolve()
    if keystore.is_relative_to(ROOT):
        raise ValueError('Keep the release keystore outside the repository')
    if keystore.exists() or certificate.exists():
        raise ValueError(f'{keystore if keystore.exists() else certificate} exists: setup never replaces a release key')
    # A password already in the environment is the caller's to keep (a throwaway test key); otherwise make one.
    password = os.environ.get(PASSWORD)
    if not password and keychain_password() is not None:
        raise ValueError('The Keychain already has cofre-android-release: setup never replaces it')
    keychain = not password
    password = password or secrets.token_urlsafe(32)
    keystore.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    certificate.parent.mkdir(parents=True, exist_ok=True)
    run(java_home() / 'bin/keytool', '-genkeypair', '-keystore', keystore, '-storetype', 'PKCS12', '-alias', ALIAS,
        '-keyalg', 'RSA', '-keysize', '4096', '-validity', '18250', '-dname', 'CN=Cofre for Android',
        '-storepass:env', PASSWORD, '-keypass:env', PASSWORD, capture=True, env=tool_env(**{PASSWORD: password}))
    keystore.chmod(0o600)
    if keychain:
        # `security -i` reads the command from stdin, so the password never appears in a process list.
        subprocess.run(['security', '-i'], input=f'add-generic-password {" ".join(KEYCHAIN)} -w {password}\n',
                       text=True, capture_output=True)
        if keychain_password() != password:
            keystore.unlink()
            raise ValueError('Saving the password to the Keychain failed; nothing was kept')
    sha256 = keystore_certificate(keystore, password)
    certificate.write_text(certificate_text(sha256))
    print(f'Release key created.\n  keystore     {keystore}\n'
          f'  password     {"Keychain item cofre-android-release (account keystore)" if keychain else "$" + PASSWORD}\n'
          f'  certificate  {sha256}\n  written to   {certificate}: commit it.\n'
          'Back up the keystore and its password now, somewhere private and offline. Installed copies only accept\n'
          'updates signed by this key: losing it ends updates for everyone who has Cofre installed.')


def build(args):
    tag = tag_for(args.version)
    notes = args.notes.read_text().strip()
    if not notes:
        raise ValueError(f'{args.notes} is empty')
    output = args.output.expanduser().resolve()
    if output.exists() or output.is_relative_to(ROOT):
        raise ValueError('Choose a new --output directory outside the repository')
    certificate = read_certificate(args.certificate.read_text())
    revision = source_revision(args.allow_dirty)
    check_newer(args.code, public_feed())
    pinned_natives()
    keystore, password = args.keystore.expanduser().resolve(), keystore_password()
    if keystore_certificate(keystore, password) != certificate:
        raise ValueError(f"{keystore}'s certificate isn't the one in {args.certificate}")
    BUILT_APK.unlink(missing_ok=True)
    # No configuration cache: it would keep the password (encrypted) under .gradle/.
    run(ROOT / 'scripts/gradle.sh', '--no-configuration-cache', ':app:assembleRelease',
        f'-Pcofre.versionCode={args.code}', f'-Pcofre.versionName={args.version}',
        env={**os.environ, 'COFRE_KEYSTORE': str(keystore), PASSWORD: password})
    output.mkdir(parents=True)
    apk = output / apk_name(tag)
    shutil.copy2(BUILT_APK, apk)
    min_sdk = verify_apk(apk, args.code, args.version, certificate)
    sha256 = sha256_file(apk)
    (output / 'SHA256SUMS').write_text(sums_line(apk.name, sha256))
    (output / 'latest.json').write_text(feed_json(make_feed(args.version, args.code, apk.stat().st_size, sha256, min_sdk, notes)))
    (output / 'notes.txt').write_text(notes + '\n')
    (output / 'release.json').write_text(json.dumps({
        'revision': revision, 'tag': tag, 'version': args.version, 'versionCode': args.code,
        'apk': apk.name, 'sums': 'SHA256SUMS', 'feed': 'latest.json', 'notes': 'notes.txt',
        'publishable': not args.allow_dirty}, indent=2) + '\n')
    print(f'Built and verified {apk} ({apk.stat().st_size} bytes, SHA-256 {sha256}).')
    if args.allow_dirty:
        print('Dry run (--allow-dirty): publish will refuse this directory.')
    else:
        print(f'Install it over the previous release on a device, then: python3 scripts/release.py publish {output}')


def download_matches(feed):
    for attempt in range(6):
        if attempt:
            time.sleep(10)  # a fresh asset can take a moment to reach the download CDN
        try:
            digest, size = hashlib.sha256(), 0
            with urllib.request.urlopen(feed['apk'], timeout=120) as response:
                for block in iter(lambda: response.read(1 << 20), b''):
                    digest.update(block)
                    size += len(block)
            problem = f'{size} bytes, SHA-256 {digest.hexdigest()}'
            if (size, digest.hexdigest()) == (feed['size'], feed['sha256']):
                return
        except OSError as error:
            problem = str(error)
    raise ValueError(f"The public {apk_name(feed['tag'])} doesn't match the build ({problem}). The feed was NOT "
                     'published: inspect the release before anything else.')


def publish(args):
    directory = args.directory.expanduser().resolve()
    release = json.loads((directory / 'release.json').read_text())
    if release.get('publishable') is not True:
        raise ValueError('This was a dry run (--allow-dirty): build again from a clean origin/main')
    tag = tag_for(release['version'])
    apk = directory / apk_name(tag)
    feed = check_feed(json.loads((directory / 'latest.json').read_text()))
    sha256 = sha256_file(apk)
    if ((directory / 'SHA256SUMS').read_text() != sums_line(apk.name, sha256)
            or (feed['sha256'], feed['size'], feed['versionCode'], feed['tag'])
            != (sha256, apk.stat().st_size, release['versionCode'], tag)):
        raise ValueError(f'{apk.name} no longer matches its checksums: build again')
    if source_revision() != release['revision']:
        raise ValueError(f'Publish from the commit it was built from ({release["revision"][:12]}), still origin/main')
    check_newer(feed['versionCode'], public_feed())
    run('gh', 'auth', 'status', capture=True)
    if (succeeds('git', 'rev-parse', '-q', '--verify', f'refs/tags/{tag}')
            or git('ls-remote', '--tags', 'origin', f'refs/tags/{tag}')
            or succeeds('gh', 'release', 'view', tag, '--repo', REPO)):
        raise ValueError(f'{tag} already exists. Releases are never replaced: use a new version and code')
    # Immutable from here on: a tag at the built commit, then a release with the APK and its checksum.
    run('git', 'tag', tag, release['revision'])
    try:
        run('git', 'push', 'origin', f'refs/tags/{tag}')
    except subprocess.CalledProcessError:
        run('git', 'tag', '-d', tag)
        raise
    try:
        run('gh', 'release', 'create', tag, apk, directory / 'SHA256SUMS', '--repo', REPO, '--verify-tag',
            '--title', f'Cofre {release["version"]}', '--notes-file', directory / 'notes.txt',
            *(['--prerelease'] if '-' in release['version'] else []))
    except subprocess.CalledProcessError:
        raise ValueError(f'Tag {tag} is pushed, but creating its release failed; installed apps see nothing yet.\n'
                         f'Check `gh release view {tag} --repo {REPO}`. To retry, delete any draft release, then the tag\n'
                         f'(git push origin :refs/tags/{tag} && git tag -d {tag}), and publish again.') from None
    download_matches(feed)
    # Last: the feed. Only now can installed apps see the release.
    message = f'[FEATURE] Offer Cofre {release["version"]} to installed apps'
    (ROOT / FEED).parent.mkdir(exist_ok=True)
    shutil.copy2(directory / 'latest.json', ROOT / FEED)
    try:
        run('git', 'add', FEED)
        run('git', 'commit', '-m', message, '--', FEED)
        run('git', 'push', 'origin', 'HEAD:main')
    except subprocess.CalledProcessError:
        raise ValueError(
            f'{tag} is public and verified, but installed apps won\'t see it until {FEED} is on main.\n'
            f'Finish in {ROOT} (don\'t rebuild or re-upload):\n'
            f'  git log -1 --format=%s -- {FEED}   # not "{message}"? then: cp {directory}/latest.json {FEED} && '
            f'git add {FEED} && git commit -m "{message}" -- {FEED}\n'
            '  git pull --rebase origin main && git push origin HEAD:main') from None
    print(f'Published https://github.com/{REPO}/releases/tag/{tag}')
    for attempt in range(12):
        try:
            if public_feed() == feed:
                print(f'The feed offers {release["version"]} to installed apps: {FEED_URL}')
                return
        except (OSError, ValueError):
            pass
        time.sleep(10)
    print(f'The feed is on main; raw.githubusercontent.com can take about 5 minutes to serve it: {FEED_URL}')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    setup_command = commands.add_parser('setup', help='once: create the release key outside the repo')
    build_command = commands.add_parser('build', help='build, sign and verify a release; publishes nothing')
    for command in (setup_command, build_command):
        command.add_argument('--keystore', type=Path, default=KEYSTORE, help=f'default: {KEYSTORE}')
        command.add_argument('--certificate', type=Path, default=CERTIFICATE,
                             help='the release certificate SHA-256 file (default: updates/signing-certificate.txt)')
    build_command.add_argument('--version', required=True, help='e.g. 0.1.0-alpha.1 (tag v<version>)')
    build_command.add_argument('--code', type=int, required=True, help='versionCode; above the published one')
    build_command.add_argument('--notes', type=Path, required=True, help='plain-text release notes')
    build_command.add_argument('--output', type=Path, required=True, help='a new directory outside the repo')
    build_command.add_argument('--allow-dirty', action='store_true', help='local dry run; the result is unpublishable')
    publish_command = commands.add_parser('publish', help='publish a built release, then the update feed')
    publish_command.add_argument('directory', type=Path)
    args = parser.parse_args(argv)
    {'setup': setup, 'build': build, 'publish': publish}[args.command](args)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        detail = getattr(error, 'stderr', None)
        if isinstance(detail, bytes):
            detail = detail.decode(errors='replace')
        sys.exit(f'release: {error}' + (f'\n{detail.strip()}' if detail else ''))
