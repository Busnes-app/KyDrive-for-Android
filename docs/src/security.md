# Privacy and security

The authoritative reporting process and maintainer commitments are in
[SECURITY.md](https://github.com/Busnes-app/KyDrive-for-Android/blob/main/SECURITY.md).
This chapter explains what a person using the app can rely on and where protection ends.

## Data on the phone

The device token and linked account record are stored in the app's private
`noBackupFilesDir`, encrypted with a non-exportable Android Keystore AES-256-GCM key.
The per-link P-256 request-signing key also stays in Keystore. Display preferences and
the salted, iterated PIN verifier are separate from that token; the verifier is mixed
with a Keystore HMAC key. Raw PINs are not saved.

The current app streams opened files instead of maintaining a persistent offline file
cache. **Save to this phone** deliberately creates a copy at the destination you choose.
That destination and any external viewer have their own storage and confidentiality
rules. KyDrive cannot revoke or erase copies they already received.

Android backup and device-transfer extraction are disabled. There is no Android-session
export or restore feature. If the installation or its Keystore keys are lost, link
again; authoritative files remain on the server. Server disaster recovery is the
server operator's responsibility, not a backup performed by this Android client.

## Network trust

The app uses HTTPS to the confirmed server origin, with Android's system CAs and normal
hostname verification. It refuses redirects and cleartext traffic. The certificate
strength policy adds checks during the TLS handshake: RSA keys of at least 2048 bits,
elliptic-curve keys of at least 256 bits, and SHA-2 or stronger verified certificate
signatures across the validated chain. The self-signature of a trust anchor is not
used as a security proof; the anchor's key strength is still checked.

The current policy accepts measurable RSA and EC certificate keys and named SHA-2
signature algorithms. Ed25519 keys and RSASSA-PSS certificate signatures are currently
refused because this policy does not measure them; that refusal is not a claim that
those algorithms are weak. Operators need a certificate chain the policy supports.

Each linked-device request is signed using its non-exportable P-256 key and SHA-256.
The signed request format binds origin, method, target, timestamp, nonce, selected
state-changing headers and body digest. The server enforces device permissions and
replay checks. These signatures supplement HTTPS; they do not hide file contents
from the server or from a TLS endpoint the phone trusts.

Dedicated developer screenshot/test builds can trust a throwaway CA through Android's
debug-only configuration. This does not enable user CAs or certificate bypass in a
production build.

## Screens and app lock

Production screens use Android's secure-window and overlay protection. Text fields ask
keyboards not to learn from entered text. An installed keyboard and Android remain
part of the trusted device environment; these requests are not a defense against a
compromised OS or malicious root-level software.

The optional PIN/strong-biometric lock gates the screen and further reads through
KyDrive's shared-file stream. It does not require user authentication for the device
token/signing key because in-process background transfers must continue while locked.
Set a secure Android screen lock as well as the app lock.

## Collection and limits

The app has no analytics, crash SDK, advertisements or Google Play Services requirement.
It sends API requests and selected uploads to your configured KyDrive server. The
server and its operators can read its stored files: this is not end-to-end encryption.

The app cannot protect against a compromised server, root-level phone compromise,
bytes retained by another app, or copies you saved outside it. No independent security
audit is claimed. Review this chapter before relying on the pre-release app for
sensitive work.
