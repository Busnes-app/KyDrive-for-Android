# Engineering principles

- Across the Ky repositories, require automated tests for major new functionality. Address compiler/linter warnings by fixing valid warnings or documenting false positives in the source or check configuration; do not silently ignore them.

**Boundary discipline.** Validate, narrow types, and handle errors at system boundaries — CLI
args, config files, network and wire formats, external APIs. Inside the boundary, trust the
types: no redundant nil checks deep in call chains. Business logic lives in pure functions the
transport layer just calls. Do not re-export transport, storage, or framework types through a
public surface. Test: "is this data crossing a boundary right now?" If no, the validation is
redundant.

**Separate before serializing shared state.** When two concurrent actors might write the same
file, branch, key, or object, first eliminate the sharing — give each its own owned file or
key and merge at the read boundary. Two workers writing their own field into one `state.json`
is still shared mutation; `indexer-state.json` + `metrics-state.json` is not. Reach for a lock,
sequential phase, or single-writer actor only when one shared write target is a real invariant.
Treat "we need a lock" as a smell to check, not the default answer.

# DOX framework

- DOX is highly performant AGENTS.md hierarchy installed here
- Agent must follow DOX instructions across any edits

## Core Contract

- AGENTS.md files are binding work contracts for their subtrees
- Work products, source materials, instructions, records, assets, and durable docs must stay understandable from the nearest applicable AGENTS.md plus every parent AGENTS.md above it

## Read Before Editing

1. Read the root AGENTS.md
2. Identify every file or folder you expect to touch
3. Walk from the repository root to each target path
4. Read every AGENTS.md found along each route
5. If a parent AGENTS.md lists a child AGENTS.md whose scope contains the path, read that child and continue from there
6. Use the nearest AGENTS.md as the local contract and parent docs for repo-wide rules
7. If docs conflict, the closer doc controls local work details, but no child doc may weaken DOX

Do not rely on memory. Re-read the applicable DOX chain in the current session before editing.

## Update After Editing

Every meaningful change requires a DOX pass before the task is done.

Update the closest owning AGENTS.md when a change affects:

- purpose, scope, ownership, or responsibilities
- durable structure, contracts, workflows, or operating rules
- required inputs, outputs, permissions, constraints, side effects, or artifacts
- user preferences about behavior, communication, process, organization, or quality
- AGENTS.md creation, deletion, move, rename, or index contents

Update parent docs when parent-level structure, ownership, workflow, or child index changes. Update child docs when parent changes alter local rules. Remove stale or contradictory text immediately. Small edits that do not change behavior or contracts may leave docs unchanged, but the DOX pass still must happen.

## Hierarchy

- Root AGENTS.md is the DOX rail: project-wide instructions, global preferences, durable workflow rules, and the top-level Child DOX Index
- Child AGENTS.md files own domain-specific instructions and their own Child DOX Index
- Each parent explains what its direct children cover and what stays owned by the parent
- The closer a doc is to the work, the more specific and practical it must be

## Child Doc Shape

- Create a child AGENTS.md when a folder becomes a durable boundary with its own purpose, rules, responsibilities, workflow, materials, or quality standards
- Work Guidance must reflect the current standards of the project or user instructions; if there are no specific standards or instructions yet, leave it empty
- Verification must reflect an existing check; if no verification framework exists yet, leave it empty and update it when one exists

Default section order:
- Purpose
- Ownership
- Local Contracts
- Work Guidance
- Verification
- Child DOX Index

## Style

- Keep docs concise, current, and operational
- Document stable contracts, not diary entries
- Put broad rules in parent docs and concrete details in child docs
- Prefer direct bullets with explicit names
- Do not duplicate rules across many files unless each scope needs a local version
- Delete stale notes instead of explaining history
- Trim obvious statements, repeated rules, misplaced detail, and warnings for risks that no longer exist

## Closeout

1. Re-check changed paths against the DOX chain
2. Update nearest owning docs and any affected parents or children
3. Refresh every affected Child DOX Index
4. Remove stale or contradictory text
5. Run existing verification when relevant
6. Report any docs intentionally left unchanged and why

## Purpose
KyDrive for Android is the native Android client for a self-hosted KyDrive server
(`Busnes-app/kydrive-server`): browse, preview, upload, share in, photo backup and offline
copies. It is not a two-way folder sync client; that is `kydrive-desktop`. iOS follows, so
the wire layer stays portable. The approved design and its owner decisions are
`docs/DESIGN.md`.

## Ownership
Root owns the product contract, the build, CI and `docs/DESIGN.md`. The server's
`internal/api` and `internal/drive` own every wire shape; this repo never invents one. A
change the server must make goes in a separate `kydrive-server` PR.

## Local Contracts
- Linking is by QR code only (owner, 2026-11-15). The web app shows
  `kydrive://link?srv=<origin>&code=<code>`. The code is single use, lasts 120 s and
  needs step-up to show. The app redeems it with `POST /api/devices/token`,
  `grant=qr&code=&name=&platform=android`. There is no sign-in on the phone and no
  confirm-a-number step.
- The `kyd_` device token is encrypted under a non-exportable Android Keystore AES-GCM key,
  stored in `noBackupFilesDir`. It is never logged, put in a URL or shown.
- `device_revoked` and Unlink always wipe: delete the Keystore keys first, then the cache,
  jobs and token. A wipe marker makes the wipe resume after a crash. The phone holds no
  unsaved user work.
- TLS only, against system CAs only. Never cleartext, never user-installed CAs, with no
  option to enable either (owner, 2026-11-15). Refuse redirects.
  The one exception is the throwaway CA `scripts/screenshot-server.sh` makes per run,
  trusted through `debug-overrides` only in the debuggable APK built with
  `-PscreenshotCa`. `src/main` and every other build trust system CAs only.
- `FLAG_SECURE` on every screen, except debug builds with `-PallowScreenshots=true`.
  `KeyboardPrivacy` on every text field. No `android.util.Log` in main sources.
  `allowBackup=false`, and the data-extraction rules exclude every domain.
- The app lock gates the UI only; background work keeps the token. The PIN is 8–12 digits,
  stored as PBKDF2 mixed with a non-exportable Keystore HMAC key, and every PIN check goes
  through `AppLock.attempt` (delay ladder, erase after 30 wrong PINs by default). Turning
  the lock off or weakening it asks for the PIN. Biometric unlock is strong-only and bound to
  a key that a new enrolment invalidates. Locking ends every file handed to another app, and
  `OpenProvider` serves nothing while locked. A wipe removes the lock with everything else.
- The default build has no Google Play Services and no proprietary SDKs, so F-Droid can
  ship it. No analytics and no crash SDK.
- The wire layer is `org.kysecurity.drive.core`: no Android imports, so iOS can mirror it.
  It uses the platform's `HttpURLConnection` and `org.json`.
- The look and the Android conventions follow `KyPost-for-Android`: Busnes palettes, the
  adaptive shell with the rail on the right, Activity Embedding and app lock. The
  benchmark is Google Drive.

## Work Guidance
- Gradle, emulators and instrumented tests run on the Major build host, never on the
  owner's workstation, each inside a memory-capped `systemd-run` scope.
- Every PR description is a change brief. PRs carry light and dark screenshots of every
  changed screen, on a phone and a tablet.
- Do not tag or cut releases.

## Verification
- `./gradlew testDebugUnitTest lintDebug checkExportedComponentsDebug assembleRelease`
  is what CI runs. `SourceRulesTest` checks the rules above that a reviewer would
  otherwise have to remember: no logging, every activity extends `SecureActivity` (zxing's
  scanner calls `secureWindow` instead), `core` has no Android imports, and the backup and
  TLS settings in the manifest and `res/xml`.
- `RealServerTest` runs the wire layer against a real `kydrive-server` when
  `KYDRIVE_SERVER_BIN` names a built binary, and is skipped otherwise. The `ci-real-server`
  job builds the pinned commit in `.github/workflows/ci.yml` and fails if the test skipped.
  Bump that pin when the app needs a newer route.
- `checkExportedComponents<Variant>` fails unless the merged manifest exports exactly
  `allowedExportedComponents` in `app/build.gradle.kts`.
- A new or bumped dependency means regenerating `gradle/verification-metadata.xml` with
  `--write-verification-metadata sha256` in its own commit. CI fails if it changes.
  Generate against an empty Gradle home (`-g <new dir>`) and the full CI task list: a warm
  cache skips the BOMs and parent POMs that a CI runner downloads. Empty `<components>`
  first: Gradle never prunes, and Scorecard reports every version left in the file.
- Advisory floors for build tools are the `build-tool-floors` bundle in
  `gradle/libs.versions.toml`. They apply to the root buildscript classpath and to
  `androidLintTool`, which lint resolves on its own.
- `scripts/screenshots.sh` captures every screen into `docs/screenshots/`, light and dark,
  phone and tablet, on its own AVDs. With `KYDRIVE_SERVER_BIN` set it starts a seeded
  throwaway server (`scripts/screenshot-server.sh`, behind socat TLS), links each emulator
  through the `screenshot_link` extra and captures the linked screens too. The
  `screenshots` workflow does that on every PR that touches `app/src/main` and uploads the
  PNGs as an artifact; attach them to the PR. Add each new screen to the script.

## Child DOX Index
- `docs/` — design and owner decisions (`DESIGN.md`).
