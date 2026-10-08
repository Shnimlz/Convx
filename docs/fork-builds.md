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

## FLAC and Data Saver

In **Settings → Player → Download format**, choose **FLAC lossless (16-bit)**
for new downloads. This resolves a matching direct lossless source and verifies
the native FLAC header; it never transcodes lossy audio or silently substitutes
AAC/Opus. Some songs or source instances may be unavailable. Files remain in
Convx's offline storage, rather than being exported to the phone's Downloads folder.
Existing and interrupted downloads keep their format. Remove a download first
if you want to download it in another format.

**Settings → Content → Data Saver Mode** reduces streaming quality for both
YouTube and JioSaavn, bypasses lossless streaming sources, and disables automatic
lyrics fetching and decorative canvas video in both players. Downloads wait for
an unmetered connection (usually Wi-Fi), retaining the format you selected.
Changing streaming quality or Data Saver does not delete downloaded songs.

The beta label is inherited from upstream; it is not a nonfunctional placeholder.
Unit tests and APK compilation cover the policy changes, but real-device traffic
measurements and provider availability still need to be checked on your network.

## Local-only mode

Completed Convx downloads are included in Home songs, the Local Songs tab and song search while Local-only mode is enabled, including verified FLAC downloads. Files scanned from MediaStore retain their separate identity; downloads are never marked as device files. Incomplete downloads and remote-only library songs are excluded from these song lists. Playback uses a read-only download cache with no HTTP fallback and reports a missing-download error if audio is unavailable. Album/artist grouping and playlists still follow the existing beta UI; this does not export cached audio as public files.
