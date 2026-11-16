# Contributing to KyDrive for Android

Read [README.md](README.md), [SECURITY.md](SECURITY.md), the
[development guide](docs/src/development.md) and the applicable AGENTS.md chain before
changing behavior. The [suite contribution policy](https://github.com/Busnes-app/.github/blob/main/CONTRIBUTING.md)
applies here too.

## Setup and checks

Use the committed Gradle wrapper, JDK 25 and Android SDK API 36.1. Agent Android builds
run on Major in a memory-capped systemd scope. Full commands and integration/device
requirements are in [Development and testing](docs/src/development.md).

```sh
./gradlew testDebugUnitTest lintDebug checkExportedComponentsDebug checkExportedComponentsRelease assembleRelease
scripts/check-no-binaries.sh
git diff --exit-code -- gradle/verification-metadata.xml
mdbook build docs
python3 scripts/check-docs.py
```

Major functionality must include automated tests. Run affected checks during iteration
and all required checks before hand-back. Record the exact commands, results and skips;
do not report an integration test as passing when its missing server caused it to skip.
Fix valid compiler/linter warnings and document false positives in source or check configuration.

## Security and product boundaries

- Preserve HTTPS/system-CA trust, certificate strength, hostname validation and redirect refusal.
- Keep tokens and signing keys in Android Keystore-backed private storage; never log secrets.
- Preserve secure windows, keyboard privacy, backup exclusions, revocation/wipe and lock gates.
- Keep the portable `core` wire layer free of Android imports. The server owns wire shapes;
  server changes belong in a separate KyDrive server PR.
- New network, storage, permission or cryptographic behavior must be documented and tested.
- Production push relays are disabled in throwaway systems: `PUSH_RELAY_URL=` and `APNS_RELAY_URL=`.

## Documentation

Use the suite's mdBook format under `docs/`, as in KyPost. Update current user chapters
when behavior changes, keep roadmap material in `docs/DESIGN.md`, and update the owning
AGENTS.md when a durable contract changes. Build the book and run its link checker.

## Pull requests

Open an issue for major proposed features. Send focused bug fixes and documentation
changes directly. Each PR describes the user effect, security implications, checks and
limits using a change brief. UI changes include matching phone/tablet screenshots in
light and dark. Disclose material AI assistance and what was independently verified.
Releases and tags require the owner's separate authorization.

## Licence

Contributions are made under the MIT licence; see [LICENSE.txt](LICENSE.txt). Preserve
upstream dependency and font notices.
