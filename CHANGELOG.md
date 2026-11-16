# Changelog

## 0.1.0 — unreleased

- Project skeleton: Gradle build with dependency verification, CI, the Busnes light and
  dark theme with Space Grotesk, a Files and Shared shell (bottom bar on phones, rail on
  tablets) and Settings (mode, rail side, about).
- Security baseline: `FLAG_SECURE` and overlay protection on every screen, no backups or
  device transfer, TLS against system CAs only.
- QR device linking with server/account confirmation and a per-link Android Keystore
  P-256 key that signs API requests.
- Personal and shared workspace browsing, streamed open in another app, verified save
  to device, resumable uploads, folders, rename, trash, undo and restore.
- Optional PIN/strong-biometric app lock, persistent attempt delays and configurable
  wipe threshold. Unlink/revocation erases local keys and session data.
- Automated unit, real-server, emulator lifecycle and QR parser fuzz tests; Android
  lint, exported-component checks, dependency verification and CodeQL in CI.
- Current user instructions, version/release policy, security reporting and live
  OpenSSF Best Practices/Scorecard badges.
- Certificate strength checks during the HTTPS handshake: RSA 2048 / EC 256 minimum
  keys and SHA-2 verified signatures across the platform-validated chain, including
  the anchor's key. Only the final self-issued anchor's unused signature is exempt.
- A 14-chapter mdBook manual, contributor guide and documentation build/link checks in CI.

No user release has been published. Upgrading between development commits may require
relinking a phone when the server's device protocol changes. Planned photo backup,
persistent offline copies and the DocumentsProvider are not included in this version.
