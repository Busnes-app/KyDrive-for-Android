# Security policy

KyDrive for Android is pre-release software. There are no supported production releases
yet; security fixes target current `main`. No independent security audit is claimed.

## Report privately

Use the repository's Security → Report a vulnerability action. If unavailable, email
hello@busnes.app with "Security: KyDrive Android" in the subject. Include affected
commit/version, impact and reproduction steps, omitting real device tokens and private
files. Keep the report private until a fix and coordinated disclosure are ready.

We acknowledge reports within two business days, then provide severity and timeline.
Target disclosure is 30 days for critical, 60 for high and 90 for moderate issues; fixes
precede disclosure. These are the [suite reporting commitments](https://github.com/Busnes-app/.github/blob/main/SECURITY.md).

## Development and release practices

- At least one primary developer understands secure-design principles, including
  fail-safe defaults, complete mediation, least privilege, separation of privilege,
  small mechanisms, open design, least common mechanism, usable interfaces, limited
  attack surface and input allowlists, and common Android implementation errors and
  mitigations.
- Major functionality includes automated tests. Compiler/linter warnings are fixed;
  false positives are documented in source or check configuration.
- Full CI, Android lint, CodeQL and dynamic tests/fuzzing run before production release.
  Confirmed medium-or-higher exploitable static/dynamic findings are resolved before
  release, and critical vulnerabilities are addressed promptly.
- Every user release has a unique version and source commit, human-readable notes and
  identification of already-public CVE/advisory fixes. See [README.md](README.md#versions-and-release-notes).

The owner and primary developer confirmed on 2026-11-16 that these knowledge and
remediation practices apply to this repository; no private vulnerability reports have
been received, no valid credentials have leaked, and no known vulnerabilities remain
unfixed. This is a maintainer attestation, not an independent full-history credential scan.

## Trust boundaries

The phone trusts Android system CAs, the KyDrive server and the device's Android
Keystore. The server can read its stored files; this is not end-to-end encryption.
Another app that receives file bytes can retain them. Rooted-device malware and a
compromised server are outside the app's protection.

Device tokens are encrypted with Keystore AES-256-GCM. Requests use P-256 ECDSA with
SHA-256; the PIN verifier uses salted PBKDF2-HMAC-SHA256 and a Keystore HMAC pepper.
Cryptographic primitives are delegated to platform providers. Keys, IVs, salts and
request nonces use provider randomness or `SecureRandom`. The published suite request
format lives in `core/Signing.kt` with the server's golden test vector. This is an
application message format over standard signatures and TLS; it is not a new cipher or
key-exchange algorithm.

Production disables cleartext, user CAs, redirects, backups, logs and screen capture.
Dedicated screenshot builds can trust a throwaway test CA and enable screenshots;
these debug capabilities are excluded from production configuration.
