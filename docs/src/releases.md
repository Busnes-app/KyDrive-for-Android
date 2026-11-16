# Versions and releases

KyDrive Android has no published user release yet. Its current development version is
`0.1.0`, with Android `versionCode` 1. Identify a development build by its full Git
commit ID as well as its display version; a version name alone does not distinguish
unreleased snapshots.

The human-written [changelog](https://github.com/Busnes-app/KyDrive-for-Android/blob/main/CHANGELOG.md)
records the changes in the current development series. It describes browsing,
transfers, linking, locking and security/verification changes, with explicit limits.
The Android Settings → About screen shows the display version.

## Release requirements

Before publishing a user release:

1. Obtain the owner's release authorization. Agent badge or documentation work does
   not authorize a tag, release or store submission.
2. Assign a unique version name, increase Android's version code, and record the exact
   source commit. Keep release identifiers retrievable from version control.
3. Promote the changelog into dated human-readable release notes. Explain meaningful
   behavior and upgrade effects, including account relinking or server compatibility.
4. Identify each fixed publicly known project vulnerability that already has a CVE or
   equivalent advisory identifier. Do not silently describe a security fix as routine cleanup.
5. Pass full build/test/lint/exported-component checks, integration/device tests, static
   and dynamic analysis, and resolve confirmed security findings before release.
6. Verify production configuration excludes test CAs, fixture keys and screenshot
   exceptions; use an owner-approved signing/distribution process.

Installing an APK signed with another key is not an ordinary update. Document channel
or signing-key migrations before release, including whether uninstall/relink is needed
and what local data is removed.

The [Best Practices form](https://www.bestpractices.dev/en/projects/16826/passing) reports
project practice status, not release readiness or an independent security audit. Read
the live form for its current status rather than assuming every source snapshot has
earned or retained a badge.
