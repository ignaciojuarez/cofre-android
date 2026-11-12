#!/usr/bin/env python3
"""scripts/release.py's pure parts. Run: python3 -I scripts/test_release.py"""
import hashlib
import json
import pathlib
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # also under python3 -I
import release  # noqa: E402

SHA = 'ab' * 32
APK = 'https://github.com/ignaciojuarez/cofre-android/releases/download/v0.1.0-alpha.1/Cofre-v0.1.0-alpha.1.apk'


def feed(**changes):
    return {**release.make_feed('0.1.0-alpha.1', 2, 109340670, SHA, 28, 'First alpha.\nControllers work.'), **changes}


class Versions(unittest.TestCase):
    def test_tags(self):
        for version in ['0.1.0', '0.1.0-alpha.1', '1.20.3-rc.2', '10.0.0-beta']:
            self.assertEqual(release.tag_for(version), 'v' + version)

    def test_rejects_other_versions(self):
        for version in ['', 'v0.1.0', '0.1', '0.1.0.1', '01.0.0', '0.1.0-', '0.1.0-alpha..1', '0.1.0+build',
                        '0.1.0 ', '0.1.0-a/b', '0.1.0\n']:
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.tag_for(version)

    def test_asset(self):
        self.assertEqual(release.apk_name('v0.1.0-alpha.1'), 'Cofre-v0.1.0-alpha.1.apk')
        self.assertEqual(release.apk_url('v0.1.0-alpha.1'), APK)


class Feed(unittest.TestCase):
    def test_contract(self):
        self.assertEqual(json.loads(release.feed_json(feed())), {
            'versionCode': 2, 'versionName': '0.1.0-alpha.1', 'tag': 'v0.1.0-alpha.1', 'apk': APK,
            'size': 109340670, 'sha256': SHA, 'minSdk': 28, 'notes': 'First alpha.\nControllers work.'})

    def test_notes_are_trimmed(self):
        self.assertEqual(release.make_feed('0.1.0', 1, 1, SHA, 28, '\n  Notes\n\n')['notes'], 'Notes')

    def test_rejects_broken_feeds(self):
        for change in [dict(sha256=SHA.upper()), dict(sha256='ab'), dict(versionCode=0), dict(versionCode=True),
                       dict(versionCode='2'), dict(size=0), dict(size=1.5), dict(minSdk=None), dict(tag='v0.1.0'),
                       dict(apk='https://example.com/Cofre-v0.1.0-alpha.1.apk'), dict(versionName='0.1'),
                       dict(notes=None), dict(extra=1)]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                release.check_feed(feed(**change))
        for key in release.FEED_KEYS:
            broken = feed()
            del broken[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                release.check_feed(broken)
        for value in [None, [], 'text']:
            with self.assertRaises(ValueError):
                release.check_feed(value)


class Newer(unittest.TestCase):
    def test_first_release(self):
        release.check_newer(1, None)

    def test_code_must_increase(self):
        release.check_newer(3, feed())
        for code in [-1, 0, 1, 2]:
            with self.subTest(code=code), self.assertRaises(ValueError):
                release.check_newer(code, feed())

    def test_unreadable_public_feed(self):
        with self.assertRaises(ValueError):
            release.check_newer(5, {'versionCode': 2})


class Checksums(unittest.TestCase):
    def test_sums_line(self):
        self.assertEqual(release.sums_line('Cofre-v0.1.0.apk', SHA), f'{SHA}  Cofre-v0.1.0.apk\n')

    def test_sha256_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory, 'file')
            path.write_bytes(b'x' * 3_000_000)
            self.assertEqual(release.sha256_file(path), hashlib.sha256(b'x' * 3_000_000).hexdigest())


class ToolOutput(unittest.TestCase):
    APKSIGNER = '''Signer #1 certificate DN: CN=Cofre for Android
Signer #1 certificate SHA-256 digest: {}
Signer #1 certificate SHA-1 digest: 0123456789abcdef0123456789abcdef01234567
Signer #1 certificate MD5 digest: 0123456789abcdef0123456789abcdef
'''
    BADGING = '''package: name='com.ignacio.cofre' versionCode='2' versionName='0.1.0-alpha.1' platformBuildVersionName='17' compileSdkVersion='37'
minSdkVersion:'28'
targetSdkVersion:'37'
application-label:'Cofre'
'''

    def test_signers(self):
        self.assertEqual(release.signers(self.APKSIGNER.format(SHA)), [SHA])
        two = self.APKSIGNER.format(SHA) + self.APKSIGNER.format('cd' * 32).replace('#1', '#2')
        self.assertEqual(release.signers(two), [SHA, 'cd' * 32])
        self.assertEqual(release.signers('DOES NOT VERIFY\n'), [])

    def test_signers_per_scheme(self):  # build-tools 37
        lines = '''V2 Signer: certificate DN: CN=Cofre for Android
V2 Signer: certificate SHA-256 digest: {0}
V2 Signer: certificate SHA-1 digest: 55e8789f3e66a14f8b33ed158d40f9560cbecc07
V3 Signer: certificate SHA-256 digest: {0}
V3.1 Signer #2: certificate SHA-256 digest: {1}
'''
        self.assertEqual(release.signers(lines.format(SHA, SHA)), [SHA])
        self.assertEqual(release.signers(lines.format(SHA, 'cd' * 32)), [SHA, 'cd' * 32])

    def test_badging(self):
        self.assertEqual(release.badging(self.BADGING), ('com.ignacio.cofre', 2, '0.1.0-alpha.1', 28))
        self.assertEqual(release.badging(self.BADGING.replace('minSdkVersion', 'sdkVersion'))[3], 28)
        for broken in ['', self.BADGING.splitlines()[0], self.BADGING.replace("versionCode='2'", "versionCode=''")]:
            with self.assertRaises(ValueError):
                release.badging(broken)

    def test_certificate_file(self):
        self.assertEqual(release.read_certificate(release.certificate_text(SHA)), SHA)
        for broken in ['', '# comment only\n', SHA.upper(), ':'.join([SHA[i:i + 2] for i in range(0, 64, 2)]),
                       f'{SHA}\n{SHA}\n']:
            with self.subTest(broken=broken), self.assertRaises(ValueError):
                release.read_certificate(broken)


class Publish(unittest.TestCase):
    """A dry run or a changed APK never reaches git or GitHub."""

    def staged(self, directory, publishable=True, apk=b'apk bytes'):
        out = pathlib.Path(directory)
        (out / 'Cofre-v0.1.0-alpha.1.apk').write_bytes(apk)
        sha = hashlib.sha256(b'apk bytes').hexdigest()
        (out / 'SHA256SUMS').write_text(release.sums_line('Cofre-v0.1.0-alpha.1.apk', sha))
        (out / 'latest.json').write_text(release.feed_json(feed(sha256=sha, size=len(b'apk bytes'))))
        (out / 'release.json').write_text(json.dumps(dict(revision='abc', tag='v0.1.0-alpha.1', version='0.1.0-alpha.1',
                                                          versionCode=2, publishable=publishable)))
        return SimpleNamespace(directory=out)

    def refuses(self, args, message):
        with mock.patch.object(release, 'run') as run, mock.patch.object(release, 'succeeds') as succeeds, \
                mock.patch.object(release, 'public_feed') as public:
            with self.assertRaisesRegex(ValueError, message):
                release.publish(args)
        run.assert_not_called()
        succeeds.assert_not_called()
        public.assert_not_called()

    def test_dry_run(self):
        with tempfile.TemporaryDirectory() as directory:
            self.refuses(self.staged(directory, publishable=False), 'dry run')

    def test_changed_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            self.refuses(self.staged(directory, apk=b'other bytes'), 'checksums')


if __name__ == '__main__':
    unittest.main()
