# Fork APK builds

`Fork APK and Release` builds a universal GMS debug APK on pushes to `main`.
It can also be started from **Actions → Fork APK and Release → Run workflow**.
Enable Actions on the fork once if GitHub asks. No upstream release signing
secrets are required. Download the `.apk` directly from **Releases** on Android;
the same APK and checksum are also attached to the Actions run as an artifact.

The workflow runs the download regression tests before compiling and publishing.
Each run creates a version such as `1.5.3-fork.1` and an increasing Android
version code. Failed builds do not publish a release.

## Signing and upgrades

The debug package (`com.convx.music.debug`) can coexist with stable Convx.
By default the runner creates a temporary debug signing key. Later builds may
require uninstalling the previous debug build, which erases its local data;
export your library first. This build is intended for testing the fixes.

For upgrades without reinstalling, provide a base64-encoded, privately stored
debug keystore in the repository Actions secret `FORK_DEBUG_KEYSTORE`, using
alias `androiddebugkey` and passwords `android`. Keep using the same keystore
for every build. Never commit it or upload it as a public release asset.

The upstream stable/nightly workflows are restricted to their original repository,
because their release keystore is not inherited by forks.
