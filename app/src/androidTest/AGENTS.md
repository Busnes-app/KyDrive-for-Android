# Purpose
Device regressions that need a real Android runtime: app-lock lifecycle, protected-screen
enforcement, and the certificate floor on the app's own HTTPS path.

# Ownership
Owns `LockRecreationTest` and `CertFloorDeviceTest`, and future tests requiring a real Activity
lifecycle or the platform's own TLS stack.

# Local Contracts
- Use a disposable emulator and synthetic in-memory lock state; restore the process singleton
  after each test.
- Block derivation deterministically so the replacement activity starts before stale setup
  completes.
- TLS tests go through `driveApi`, never a hand-built client, and read their certificates from
  the androidTest assets `scripts/tls-fixtures.sh` generates. A case that must be refused
  asserts that the test server recorded no request line, which is what "before the request"
  means for the device token. Skip rather than weaken when the APK does not trust the fixture
  CA: only a debuggable build with `-PscreenshotCa` does.

# Verification
- `./gradlew connectedDebugAndroidTest` (`ci-lock-recreation`, API 36).
- `./gradlew connectedDebugAndroidTest -PscreenshotCa=app/src/test/resources/tls/ca.pem
  -Pandroid.testInstrumentationRunnerArguments.class=org.kysecurity.drive.CertFloorDeviceTest`
  (`ci-tls-floor`, API 36), which fails if the tests skipped.

# Child DOX Index
None.
