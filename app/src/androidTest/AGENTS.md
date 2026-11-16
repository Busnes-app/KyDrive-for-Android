# Purpose
Device regressions for app-lock lifecycle and protected-screen enforcement.

# Ownership
Owns `LockRecreationTest` and future tests requiring a real Activity lifecycle.

# Local Contracts
- Use a disposable emulator and synthetic in-memory lock state; restore the process singleton after each test.
- Block derivation deterministically so the replacement activity starts before stale setup completes.

# Verification
- `./gradlew connectedDebugAndroidTest` (`ci-lock-recreation`, API 36).

# Child DOX Index
None.
