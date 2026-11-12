# App updates

Cofre updates itself from GitHub: the APKs are GitHub Release assets and the update feed is a JSON file on `main`. No update server, account or store is involved.

**State (2026-11-11):** `scripts/release.py` is built and a full local dry run passed: signed with a throwaway key, verified, unpublished. Nothing is released yet. The signing key doesn't exist yet: `setup` creates it before the first release.

"Release" means a GitHub Release **and** the feed on `main`. A merged PR alone isn't a release; an APK uploaded anywhere else is invisible to installed apps.

## How installed apps update

The feed is `updates/latest.json`, read from `https://raw.githubusercontent.com/ignaciojuarez/cofre-android/main/updates/latest.json`:

```json
{
  "versionCode": 2,
  "versionName": "0.1.0-alpha.1",
  "tag": "v0.1.0-alpha.1",
  "apk": "https://github.com/ignaciojuarez/cofre-android/releases/download/v0.1.0-alpha.1/Cofre-v0.1.0-alpha.1.apk",
  "size": 109340670,
  "sha256": "<64 lowercase hex digits>",
  "minSdk": 28,
  "notes": "Plain text, newlines allowed."
}
```

- A 404 means nothing is released yet.
- The app offers the update only when `versionCode` is greater than its own and the device meets `minSdk`.
- It downloads the APK from a GitHub release URL, checks `size` and `sha256`, and checks that the APK is signed by the same certificate as the installed app. Then Android's installer asks the user to confirm. The first time, Android asks to allow Cofre to install apps.
- Android itself refuses an update signed by a different key, and refuses lower version codes.

**Trust:** the feed isn't signed. The release signing key is the trust anchor. Someone who could edit the feed can only point it at an APK that fails the checks or that Android refuses. Shipping code to installed apps needs the key.

**Other ways to install:** the [releases page](https://github.com/ignaciojuarez/cofre-android/releases) has every APK with its `SHA256SUMS`. [Obtainium](https://github.com/ImranR98/Obtainium) works with the repo URL; turn on pre-releases while versions are alphas. Both install the same signed APK, so they mix with in-app updates.

**Builds from source** are signed with your local debug key. Android won't update them to a release, or a release to them: uninstall first, which deletes the app's data (instances included).

## Signing key setup (once)

```sh
python3 scripts/release.py setup
```

- Creates `~/Library/Application Support/Cofre/release/cofre-release.jks` (RSA 4096, alias `cofre`, valid 50 years) outside the repo. `--keystore` picks another path.
- Uses `keytool` from the JDK `scripts/gradle.sh` uses.
- Stores a random password in the login Keychain as `cofre-android-release` (account `keystore`). It never appears in a command line or output.
- Refuses to replace an existing keystore, Keychain item or certificate file.
- Writes the certificate's SHA-256 to `updates/signing-certificate.txt`. Commit that file: every build checks against it, and users can too (`apksigner verify --print-certs`).

**Back up the key now.** Copy the keystore and its password (`security find-generic-password -s cofre-android-release -a keystore -w`) somewhere private and offline, such as an encrypted disk or a password manager. Losing either ends updates: every installed copy would have to uninstall, losing its data, to move to a new key. Never commit either.

On another Mac: copy the keystore to the same path, then run `security add-generic-password -s cofre-android-release -a keystore -w`. With `-w` last, it asks for the password instead of taking it on the command line.

Forks must use their own key, application ID and feed URL.

## Release command

Requires Python 3.9+, Android Studio's JDK (as `scripts/gradle.sh` uses), the Android SDK build-tools, `gh auth login`, and the pinned natives (`scripts/natives/fetch.sh <cofre.natives>`).

```sh
git fetch origin && git switch --detach origin/main
python3 scripts/release.py build --version 0.1.0-alpha.1 --code 2 \
  --notes ~/cofre-notes.txt --output ~/CofreReleases/0.1.0-alpha.1
# install the APK over the previous release on a device and check it, then:
python3 scripts/release.py publish ~/CofreReleases/0.1.0-alpha.1
```

**`build`** publishes nothing. It:
1. Requires a clean checkout whose HEAD is `origin/main` (after a fetch), a new output directory outside the repo, and a `--code` above the public feed's.
2. Runs `scripts/gradle.sh :app:assembleRelease` with the version as Gradle properties and the key from the Keychain as `COFRE_KEYSTORE`/`COFRE_KEYSTORE_PASSWORD` (no configuration cache, so the password isn't kept under `.gradle/`).
3. Copies the APK to `Cofre-v<version>.apk`, then verifies it:
   - `apksigner`: one signer, the certificate in `updates/signing-certificate.txt`;
   - `zipalign -c -P 16`: 16 KB-aligned native libraries;
   - `aapt2 dump badging`: `com.ignacio.cofre` with the requested version code and name;
   - the natives are inside: `libcofre.so`, `libcofrevk.so`, `liblwjgl.so` and the JRE.
4. Writes `SHA256SUMS`, `latest.json` (the feed), `notes.txt` and `release.json` (commit, tag, file names).

`--allow-dirty` skips the checkout checks for a local dry run and marks the output unpublishable. `--keystore`, `--certificate` and a `COFRE_KEYSTORE_PASSWORD` already set in the environment point it at a throwaway test key.

**`publish`**:
1. Re-checks the checksums, and that HEAD is still the build's commit and `origin/main`.
2. Refuses if the tag or release exists, or if the public feed moved past `--code`.
3. Tags the commit `v<version>`, pushes the tag, and runs `gh release create --verify-tag` with the APK and `SHA256SUMS`. Versions with a `-` part are marked pre-release.
4. Downloads the public APK again and checks its size and SHA-256.
5. Only then copies `latest.json` to `updates/latest.json`, commits it alone (`[FEATURE] Offer Cofre <version> to installed apps`) and pushes it to `main`.

If the feed push fails after the release exists, `publish` prints the exact commands to finish. Never rebuild or re-upload for that.

**Rules:**
- `--code` always increases. Release builds start at 2, above the default `1` of local builds.
- Tags and assets are never replaced. To fix a release, ship a new version.
- Turn on release immutability in the GitHub repo settings, so even the maintainer can't swap a published APK.
- Keep keystores, passwords and APKs out of git.

## Still unqualified

- The first public release, and the first `setup` with the real key.
- An installed N → N+1 update on a real device (Odin 3): download, checks, the installer prompt, and that instances and settings survive.
- Obtainium installing and updating Cofre.
- Interrupted downloads, no network, low storage, and a device below `minSdk`.
- Google's developer verification for sideloaded apps (2027) may require registering the package and key.
