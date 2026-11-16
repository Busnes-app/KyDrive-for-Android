# KyDrive for Android

[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/16826/badge)](https://www.bestpractices.dev/projects/16826)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/Busnes-app/KyDrive-for-Android/badge)](https://scorecard.dev/viewer/?uri=github.com/Busnes-app/KyDrive-for-Android)

The Android app for [KyDrive](https://github.com/Busnes-app/kydrive-server), a self-hosted
file server. Browse personal and shared workspaces, open files in another app, save files
to your phone, upload, create folders, rename, and move items to and from trash.

This is pre-release software. The [documentation book](docs/src/introduction.md) describes the current interface;
[`docs/DESIGN.md`](docs/DESIGN.md) describes the roadmap and owner decisions. Automatic
photo backup, persistent offline copies and a DocumentsProvider are planned.

- No Google Play Services, analytics or crash reporting.
- TLS only, against the system's certificate authorities.
- Screens are hidden from screenshots and the recent-apps view.
- Optional PIN and strong biometric app lock; device keys stay in Android Keystore.

Report bugs or request features in [Issues](https://github.com/Busnes-app/KyDrive-for-Android/issues).
See the [contribution requirements](CONTRIBUTING.md)
and [security policy](SECURITY.md) before contributing or reporting a vulnerability.

## Documentation

The manual uses mdBook, the same chapter-based format as KyPost's documentation, with
navigation, search and a print view. Read its Markdown chapters here or build the book.

- [Getting started](docs/src/getting-started.md) and [linking a phone](docs/src/linking.md)
- [Files and workspaces](docs/src/files.md), [uploads and downloads](docs/src/transfers.md)
- [Settings and app lock](docs/src/settings.md), [unlinking](docs/src/unlinking.md)
- [Troubleshooting](docs/src/troubleshooting.md) and [privacy/security](docs/src/security.md)
- [Architecture/API reference](docs/src/architecture.md) and [development/testing](docs/src/development.md)
- [Versions/releases](docs/src/releases.md) and [contributing](CONTRIBUTING.md)

```sh
mdbook build docs
python3 scripts/check-docs.py
```

Open `docs/book/index.html`. CI uploads the generated book as `documentation-book`;
generated HTML is not committed. This builds documentation without running Android tooling.

## Build

```sh
./gradlew testDebugUnitTest lintDebug checkExportedComponentsDebug checkExportedComponentsRelease assembleRelease
```

Needs JDK 25 and the Android SDK (API 36.1); the app runs on Android 12 (API 31) or newer.
The Gradle wrapper and dependency checksums are committed. Android builds and emulators
for agent work run on the Major build host in a memory-capped systemd scope.

CI also runs the wire tests against a pinned real KyDrive server, a bounded Jazzer fuzz
test of QR input, and Android instrumented tests. To run the integration tests locally,
set `KYDRIVE_SERVER_BIN` to a built server binary; without it, `RealServerTest` skips.
Run device tests with `./gradlew connectedDebugAndroidTest` on a disposable emulator.

## Versions and release notes

There are no published releases yet. The current development version is `0.1.0`
(`versionCode` 1); identify a source build by its full Git commit ID as well as that
version. Each commit is a unique, retrievable development snapshot.

Before a user release, assign a new version name and increasing Android version code,
record the exact source commit, and promote the human-written [changelog](CHANGELOG.md)
into dated release notes. Include upgrade effects and every fixed publicly known
project vulnerability that already has a CVE or equivalent advisory identifier. Run the
full CI checks, lint, static and dynamic analysis and resolve confirmed security
findings before release. A badge update does not authorize a release.

## Licence

MIT, see [LICENSE.txt](LICENSE.txt). Space Grotesk is under the SIL Open Font License
([fonts/Space_Grotesk/OFL.txt](fonts/Space_Grotesk/OFL.txt)).
