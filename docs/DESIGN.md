# KyDrive for Android: design

Status: draft for owner review, 2026-11-15. Nothing is built yet.

KyDrive for Android is a native client for a self-hosted KyDrive server. People use it to
browse, open, share and back up their files from a phone. It is not a sync client: the
desktop app (`kydrive-desktop`) mirrors folders both ways, and this app reads and writes
on demand. iOS comes later, so the wire layer is kept small and free of Android imports,
which makes it easy to port.

The server's source code is the contract. Citations below refer to
`Busnes-app/kydrive-server` at `origin/master` 864c365 and `Busnes-app/kydrive-desktop` at
`origin/main` 824eb0d.

## 1. Linking

### What exists

The desktop links with a browser approval, PKCE S256 and a loopback redirect (RFC 8252):

1. The app opens `/link?redirect=&challenge=&state=&name=&platform=`. The user signs in,
   passes a step-up check and taps Approve. The web app then calls
   `POST /api/devices/approve`.
2. The browser is redirected to `redirect?code=&state=`. The code lives 60 s and is
   single use.
3. The app sends `POST /api/devices/token` (form: `code`, `verifier`). It gets back
   `{token: "kyd_…", user_id, user, devices_url}`.

The token:

- Is stored as SHA-256 on the server.
- Expires after 90 days unused.
- Is scoped to `/api/drive/*` and `/dav/`.
- Cannot share, create guest links, open the editor or mint credentials
  (`internal/drive/devices_access_test.go`).

Revocation is `DELETE /api/devices/{id}?wipe=`. After that the token answers
`401 {"error":"device_revoked","wipe":bool}` for 30 days. The app can unlink itself with
`DELETE /api/devices/self`.

### What blocks a phone today

- `validPlatform` accepts only `windows|macos|linux` (`internal/drive/devices.go:249`).
- The redirect must be `http://127.0.0.1:<port>` or `http://[::1]:<port>`
  (`devices.go:270`).
- The web copy says "Link a computer" and "Computers", and the 20-device cap is worded
  for computers.
- There is no cross-device (QR) path. A device-code design (RFC 8628) was considered and
  dropped (`docs/desktop-sync-options.md:644`).

### Proposal: two ways in, one credential

Both paths end with the same `kyd_` device token. That token is listed and revoked
under the web app's device settings, next to the computers.

**A. Scan a QR code from the web app (primary).**

1. On a computer, the user signs in to KyDrive. They open Settings → Devices → Link a
   phone and pass step-up.
2. The server mints a pairing code (256 bits, single use, 120 s, stored hashed). The page
   shows it as a QR code: `kydrive://link?srv=<origin>&code=<code>`. There is no user
   data in the QR code.
3. The app scans it with zxing in a `FLAG_SECURE` capture activity, the same as
   KyAuth-android in its F-Droid build. Before sending anything, the app:
   - checks that `srv` is HTTPS with a host and no userinfo, path or query;
   - shows "Link this phone to `<host>`?".
4. The app sends `POST /api/devices/token` with `grant=qr`, `code`, `name`,
   `platform=android`. The server returns the same body as today.
5. The web page polls `GET /api/devices`. It shows "Linked: `<name>`, just now", with a
   Revoke button.

The risk is a photographed QR code. Someone who copies it within 120 s can redeem it
first. Three things limit the damage:

- The owner's own scan then fails with "already used".
- The web page shows a device the owner doesn't recognise.
- Step-up is required to show the code.

Section 7, question 3, asks whether that is enough, or whether you want a confirm-a-number
step.

**B. Sign in on this phone (for people with no computer to hand).**

1. The app opens `/link` in a Custom Tab, with `platform=android` and
   `redirect=org.kysecurity.drive:/link`. This is a private-use URI scheme (RFC 8252
   §7.1). PKCE stops another app that claims the same scheme from using a stolen code.
2. A loopback redirect would need the server change anyway. It also depends on Chrome
   allowing a top-level navigation from https to `http://127.0.0.1`, which is unproven
   with Chrome's Local Network Access rules. Use the scheme.

**Server PR (separate, `kydrive-server`):**

- Add `android` and `ios` to `validPlatform`. For those platforms only, accept the exact
  redirect `org.kysecurity.drive:/link`.
- Add the QR pairing endpoint and the "Link a phone" page.
- Change the copy from "Computers" to "Devices", with a phone icon for mobile platforms.
- Every new Store method gets classified in `devices_access_test.go`.

### On the device

- **Where the token lives.** An AES-256-GCM key in the Android Keystore encrypts the token.
  The key is non-exportable and uses StrongBox where the phone has it. The ciphertext sits
  in `noBackupFilesDir`.
- **No user authentication on that key.** Background work (backup, the DocumentsProvider)
  has to use the token while the app is locked. The app lock gates the UI.
- **Saved alongside the token:** the server origin, user ID, user name and device name.
  These are not secrets.
- **Identity check.** After linking, `GET /api/drive/device` must return the same
  `user_id`. Re-linking to a different account is refused, as the desktop does.
- **Plain 401** (token idle for 90 days, or password changed): show "Signed out, link
  again". Keep the encrypted cache until the user relinks or unlinks.
- **`device_revoked`**: wipe, whatever the value of `wipe` (section 3).

## 2. Threat model

**Assets:**

- the device token;
- file contents and names held on the phone;
- the server itself, since the phone must not become a way to widen access.

| Threat | Defence |
|---|---|
| Network attacker | TLS only. Cleartext is off and only system CAs are trusted. The TLS floor (RSA ≥ 2048, EC ≥ 256, SHA-2) is the same as KyPost's `StrongTls` and the desktop's FIPS policy. Redirects are refused. The server is not pinned, because self-hosted certificates rotate (the desktop doesn't pin either). |
| Lost or stolen phone, unlocked | App lock (PIN or strong biometric) after a 30 s grace period, using KyPost's `LockedActivity` and `LockoutPolicy`. The user revokes the phone from the web, and the next request wipes it. |
| Lost phone, attacker reads storage (rooted, forensics) | The token and the cache are encrypted under Keystore keys that never leave the device. Unlinking deletes the keys first (crypto-shred), then the files. |
| Another app on the phone | Exported components are limited to the launcher activity, the share target, the camera-backup settings entry, and the DocumentsProvider (`MANAGE_DOCUMENTS`, so only the system picker binds). A build gate checks the merged manifest against that allowlist, as KyPost's does. |
| Files handed to another app | Out of our control once the user opens a file in another app. The app grants per-URI read access through the DocumentsProvider and never writes plaintext to shared storage. |
| Screen capture and recents | `FLAG_SECURE` on every screen, the same as KyPost's `LockedActivity`. It is off only in debug builds with `-PallowScreenshots=true`. |
| Keyboard learning | `KeyboardPrivacy` on every text field (search, rename, new folder), enforced by a source-rule test. |
| Leaks through logs and backups | No `android.util.Log` in main sources (source-rule test). File names, tokens and URLs never reach logs or crash reports, and there is no crash SDK. `allowBackup=false`, and the data-extraction rules exclude every domain. |
| Compromised or malicious server | The server already sees everything (it is not end-to-end encrypted). The app treats names from the server as untrusted: no path joins, and content is shown with the server's own type checks. The DocumentsProvider never resolves names to paths. |
| A stolen phone widening access | It can't. The device token cannot share, grant, mint credentials or create guest links (server-enforced). |

**Not defended:**

- a server operator reading files;
- malware with root on an unlocked phone;
- what another app does with a file the user opened in it.

## 3. Offline cache

Three kinds of data live on the device:

- **Thumbnails and folder listings:** in memory only, cleared when the app is killed.
- **Opened files:** an on-disk cache, used to preview a file and to serve it through the
  DocumentsProvider. Least-recently-used files are evicted past a cap (default 1 GB,
  adjustable in Settings).
- **Keep on device (M5):** pinned files that are never evicted. A WorkManager job
  refreshes them when the server revision changes. The check runs on unmetered networks by
  default.

**Encryption at rest:**

- **File contents** use Tink `StreamingAead` (AES256_GCM_HKDF_4KB). Its keyset is wrapped
  by a second Android Keystore AES-GCM key. Tink is Apache-2.0, pure Java and available to
  F-Droid. Its seekable decrypting channel serves random reads.
- **The cache index** is one small encrypted file holding file ID, revision, digest, name
  and pinned flag. It is not a database: the index holds at most a few thousand rows, and
  SQLCipher would add a native library for nothing.
- **Previews and "open with"** go through `StorageManager.openProxyFileDescriptor`, which
  decrypts on read. No plaintext file ever touches disk.
- **Downloads** are checked against the server's `digest` (SHA-256) before they enter the
  cache.

**Wipe** happens on Unlink, on `device_revoked`, and after too many failed app-lock
attempts (KyPost's policy; on by default after 30 attempts):

1. Delete both Keystore keys. This makes everything unreadable at once.
2. Delete the cache directory and index.
3. Cancel all WorkManager jobs.
4. Then delete the token.

If the app dies partway, the wipe resumes on next start. A wipe marker is written first,
which makes the wipe idempotent.

**The phone never holds unsaved user work.** Pending uploads (share-in, camera backup)
read from the sending app or from MediaStore, so wiping loses no originals. That is why a
revoke always wipes, even with `wipe=false`. The flag exists for the desktop, where local
files are the user's own.

## 4. API surface

Everything below is under `/api/drive` with `Authorization: Bearer kyd_…`, so no cookies
and no CSRF tokens. All of it is allowed for device tokens (`devices_access_test.go`).

| Feature | Calls |
|---|---|
| Who am I | `GET /device` |
| My files and shared workspaces | `GET /workspaces` returns `{id,name,kind,role,quota,used,head,trash_days,keep_versions,editors_share}`; `POST /personal-workspace` if missing |
| Shared with me | `GET /shared-with-me` |
| Browse | `GET /folders/{id}/children`; `GET /files/{id}` |
| Thumbnails and previews | `GET /files/{id}/thumbnail` (PNG ≤ 256 px, 415 if none); `GET /files/{id}/preview` (server allowlist: PNG/JPEG/GIF/WebP, PDF, MP3/WAV/Ogg, MP4/WebM, plain text) |
| Download | `GET /files/{id}/download` (Range, ETag = SHA-256) |
| Upload | ≤ 8 MiB: `POST /workspaces/{id}/uploads?parent=&name=&file=&revision=&mtime=`. Larger: `POST /workspaces/{id}/upload-sessions`, then `PUT /upload-sessions/{sid}` with `Content-Range` in 8 MiB chunks, resuming from `GET …/{sid}` or the `Upload-Offset` header, then `POST …/complete` |
| New folder | `POST /workspaces/{id}/folders {parent,name}` |
| Rename and move | `PUT /files/{id}/location {revision,workspace,parent,name}`; `PUT /folders/{id}/location {parent,name}` |
| Trash and restore | `PUT /files/{id}/trash {revision,trashed}`; `PUT /folders/{id}/trash {trashed,head}`; list with `GET /workspaces/{id}/files?trash=true` and `/folders?trash=true` |
| Versions | `GET /files/{id}/versions`; `POST /files/{id}/restore {revision,expected}` |
| Search | `GET /search?q=&contents=1` (by name and by content, ≤ 200 hits, content snippets) |
| Guest links | `GET /guest-links-policy` (decides whether the action is shown); create in the web app (section 5) |
| Keep-on-device refresh (M5) | `GET /workspaces/{id}/changes?after=` (410 means re-snapshot) |
| Unlink | `DELETE /api/devices/self` |

**What the UI does with roles:**

- Reader: every write action is hidden.
- Editor: may upload, create folders, rename, move within a workspace, trash and restore.
- Manager, or the owner of a personal workspace: may also move across workspaces.

The server enforces all of this. The UI only avoids offering actions that would fail.

**Conflicts:** every write carries the `revision` the user saw. A 409 says "Changed on the
server. Reload?". Nothing is merged and nothing is overwritten.

**Limits to respect:**

- 8 open upload sessions per user, so backup uploads one at a time.
- Sessions live 24 h.
- Maximum file size comes from the server (10 GiB by default).

## 5. Features by milestone

- **M1:** this document, then the project skeleton, then linking.
- **M2, browse:**
  - My files, Shared (workspaces with role badges, and Shared with me), Trash.
  - Open and preview: images, PDF through `PdfRenderer`, audio/video through
    `MediaPlayer`, and text. Download to a user-chosen location (SAF `CreateDocument`).
  - Upload with a SAF picker or the camera; new folder; rename; move with a folder picker;
    trash and restore.
  - Version history, with restore.
  - Search by name and by content.
  - **The DocumentsProvider:**
    - Root "KyDrive" holds one child per workspace. Opening a file uses an encrypted,
      decrypt-on-read cache. Writes go to an encrypted temp file and upload on close.
    - While the app is locked, it throws `AuthenticationRequiredException`, so the system
      picker shows "Unlock KyDrive".
    - When unlinked it exposes no root.
- **M3, share:**
  - Share-in: an `ACTION_SEND` / `SEND_MULTIPLE` target. The user picks a workspace and
    folder, then the upload runs in WorkManager.
  - Guest links: the action appears only when the admin switch is on. It opens the file in
    the web app in a Custom Tab, where step-up and the server's rules apply. Phones never
    get guest-link power, so a stolen token can't publish files.
    - The web app is hash-routed. If it has no deep link to an item's share panel, that
      is a small web PR on the server.
  - Share-out of a single file goes through the DocumentsProvider URI.
- **M4, photo and camera backup (its own PR):**
  - Opt-in. Unmetered networks by default; charging is optional. Uses WorkManager.
  - It reads MediaStore since a saved watermark and respects Android 14 partial photo
    access.
  - Files go to `Camera backup/<device>/YYYY/MM` in a workspace the user chooses.
  - Uploads resume through upload sessions. Duplicates are skipped by SHA-256 against the
    server's `digest`.
  - It never deletes from the phone.
- **M5:** keep on device, as described in section 3.

**Design and Android conventions** follow KyPost-for-Android:

- Classic Views and Material Components, not Compose.
- The Busnes palette pairs from `AppTheme.kt`, with System, Light and Dark modes.
- Space Grotesk, bundled as TTF the way KyAuth does it, because the downloadable font
  needs Play Services.
- Navigation:
  - Phones get a bottom bar. Widths of 600 dp or more, and book-fold covers, get a
    navigation rail on the right, with a left/right setting.
  - From 840 dp, the file list and the preview split side by side with Activity
    Embedding.

The benchmark is Google Drive:

- a search bar on top;
- a grid/list toggle;
- the "+" FAB for upload and new folder;
- a bottom-sheet actions menu.

The bottom bar holds three destinations: **Files**, **Shared** and **Offline** (Offline
from M5). Trash opens from the Files overflow. The account button in the top bar opens
Settings, which has these sections:

- **Account:** server, user, device name, and Unlink as an icon button.
- **Appearance:** theme, mode and rail side.
- **Security:** app lock and grace period.
- **Backup** (M4).
- **About.**

## 6. Build, test, ship

- **Repo:** `Busnes-app/KyDrive-for-Android`, public, MIT.
- **Package:** `org.kysecurity.drive`, app name "KyDrive".
- **Toolchain:** the same as KyPost: Gradle 9.8.2 with a pinned SHA-256, AGP 9.5.1, Kotlin
  2.4.21, minSdk 31, targetSdk 36, a version catalog, and dependency verification
  metadata.
- **One flavour, no Google Play Services.** The F-Droid build and the GitHub build are the
  same APK. A Play flavour can be added later if it's ever needed (YAGNI).
- **Dependencies:** OkHttp, kotlinx-serialization, Tink, zxing-android-embedded, and
  AndroidX (appcompat, material, work, window, biometric, browser for Custom Tabs).
- **Modules:**
  - `core`: pure Kotlin/JVM, with no Android imports. It holds the API client, models, QR
    and URL validation, upload resume and error mapping. It is the part iOS mirrors (as a
    Swift port, or as Kotlin Multiplatform if that is chosen then).
  - `app`: everything Android.
- **Tests:**
  - JVM unit tests for `core` and the policies (wipe, lockout, role gating).
  - Source-rule tests: no `Log`, KeyboardPrivacy, `FLAG_SECURE`, the exported-components
    allowlist.
  - A **real-server contract job**: CI builds a pinned `kydrive-server` commit and runs
    the `core` tests against it, as `kydrive-desktop` does.
- **CI on PRs:** wrapper validation, unit tests, lint, a release build with R8, and
  `check-no-binaries`. Actions are pinned by SHA. CodeQL and Scorecard run as in the
  sibling repos.
- **Screenshots:** a script run on Major (rooted emulator, debug build with
  `-PallowScreenshots=true`, a throwaway server) captures every screen in light and dark,
  on a phone and a tablet. The images live in `docs/screenshots/` and go into each PR.
- **Builds** run on Major only, under `flock` and a 6 GB `systemd-run` scope. No releases
  and no tags.

## 7. Out of scope

- Two-way folder sync. That is the desktop's job.
- The document editor (the device token is refused for `EditorSession`). Opening a file in
  the web app covers this.
- Sharing with people, changing grants, workspace administration, and managing app
  passwords or devices from the phone.
- Downloading old versions. The server has no route for an old revision's bytes; restore
  works.
- Permanent delete from trash, and copy. The server allows both; they can be added later.
- Push notifications, end-to-end encryption, WebDAV, MCP.
- A Play Store flavour, Google Play Services, analytics, crash SDKs.
- More than one account or server per app.

## 8. Questions for the owner

1. **Repo:** `Busnes-app/KyDrive-for-Android`, public, MIT?
2. **Linking:** QR from the web (A) plus sign-in on the phone (B), both in one server PR?
   Or QR only?
3. **Is the QR code safe enough?** Options:
   - single use, 120 s, step-up to show it, and the web page shows "Linked: `<name>`"
     with Revoke; or
   - also a confirm step, where the phone shows a 6-digit number that the user types or
     approves on the web page before the token works.
4. **User-installed CAs:** homelab servers often sit behind a private CA. Default off (the
   brief). Do you want a per-server opt-in in Settings, behind a warning?
