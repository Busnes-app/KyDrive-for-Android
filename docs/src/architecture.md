# Architecture and API reference

This chapter is for developers integrating or changing the Android client. The KyDrive
server source defines the API; the Android app does not define a separate wire protocol.

## Layout

| Path | Responsibility |
|---|---|
| `app/src/main/java/org/kysecurity/drive/core/` | Portable wire models, request signing, file operations, transfer and certificate policy; no Android imports |
| `MainActivity.kt`, `Rows.kt` | Files/Shared navigation, item actions and system file pickers |
| `Session.kt`, `DeviceSigningKey.kt`, `DeviceTls.kt` | Android storage, Keystore and platform TLS adapters |
| `OpenProvider.kt` | Read-only proxy file descriptors for external viewers; not a DocumentsProvider |
| `lock/`, `SecureActivity.kt`, `UnlockActivity.kt` | PIN/biometric policy and protected-screen lifecycle |
| `Work.kt` | Process-owned background transfers and user-facing error mapping |
| `app/src/test/`, `app/src/androidTest/` | JVM tests, fuzz seeds, TLS fixtures and device lifecycle/transport tests |

## Linking input and output

The QR payload is `kydrive://link?srv=<encoded-origin>&code=<code>`. `srv` must be a bare
HTTPS origin with a hostname, optional port, no user information, query or fragment.
`code` is exactly 43 base64url characters. An optional `pin=sha256/<base64>` supplies
the expected leaf public-key hash; encode it as a query value, including any `+` character.
Duplicate/unknown parameters, malformed escapes and unsupported origins are refused
before a network request.

`POST /api/devices/token` sends form fields `grant=qr`, `code`, `name`, `platform=android`
and `publicKey` (standard-base64 SPKI DER). The response supplies a `kyd_` token,
`user_id` and `user`, and may supply the server's Ed25519 `identity_key`.
`GET /api/drive/device` may also supply that key and must identify the same account before
the session is saved. `DELETE /api/devices/self` revokes the current phone.

Never log or place the device token in a URL. The app sends it as `Authorization:
Bearer …`; request signatures are separate headers. The signature golden vector in
`app/src/test/resources/device-signing-v1.json` is shared with the server.

## Certificate rollover

All HTTPS connections, including the credential-free `GET /api/devices/tls-pin` lookup,
pass platform trust and the certificate-strength floor. Credentialed requests also check
the saved leaf pin inside the same handshake. A pin mismatch permits one lookup and one
retry; the lookup sends no token or signing headers. `TlsPins.accept` requires a statement
signed by the stored identity key for the observed leaf and hostname, within 24 hours of
the phone clock and newer than the saved freshness floor. The identity key is learned
once over the pinned connection and never replaced. `tls-pin-v1.json` is shared with the
server; a missing identity key requires relinking after a certificate change.

## File operations

| Operation | Request | Result used by the client |
|---|---|---|
| Visible workspaces | `GET /api/drive/workspaces` | Array with workspace ID, name, kind, role and head |
| Ensure personal workspace | `POST /api/drive/personal-workspace` | Workspace ID, followed by a workspace reload |
| Folder/file listing | `GET /api/drive/workspaces/{id}/folders` and `/files` | Arrays; `?trash=true` selects trash |
| Direct shares | `GET /api/drive/shared-with-me` | Shared item kind, IDs, workspace name and role |
| Shared folder children | `GET /api/drive/folders/{id}/children` | `folders` and `files` arrays |
| File metadata | `GET /api/drive/files/{id}` | Revision, size, SHA-256 digest and modification time |
| New folder | `POST /api/drive/workspaces/{id}/folders` | New folder metadata |
| Rename | `PUT /api/drive/files/{id}/location` or `/folders/{id}/location` | Updated item; files include an expected revision |
| Trash/restore | `PUT /api/drive/files/{id}/trash` or `/folders/{id}/trash` | Change confirmation; the client reloads |
| Download/open | `GET /api/drive/files/{id}/download` | File bytes; range opens require `206` and bind the listed digest with `If-Range` |
| Upload | `POST /api/drive/workspaces/{id}/upload-sessions`, then `PUT /api/drive/upload-sessions/{id}` and `POST …/complete` | Session ID, accepted offset and completed file metadata |

Names from the server are display values, not local paths. API JSON responses are
bounded; malformed JSON becomes an I/O failure. Non-success replies expose a status,
structured error code and optional wipe field internally. Raw server error text is
not displayed verbatim to users.

`401` with `device_revoked` wipes regardless of its `wipe` boolean. Ordinary `401`
marks the account signed out. Other status codes map to the messages in
[Troubleshooting](troubleshooting.md).

## Change the wire contract

Read the server's current API and both repositories' AGENTS.md files before changing
a request. Put server changes in a separate server PR. Update the shared signature
vector and interoperability tests when its format changes. Bump the real-server CI
pin when a client needs newer routes; a local test against a different server is not
evidence that the pinned CI integration ran.
