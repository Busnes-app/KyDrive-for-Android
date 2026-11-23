# Purpose
Owns KyDrive Android's mdBook manual and the approved product design.

# Ownership
- `src/` and `book.toml` own the published manual; `src/SUMMARY.md` is its chapter index.
- `DESIGN.md` owns the roadmap and owner decisions, not current user instructions.
  Its section 5.1 owns the photo-backup inventory, remaining acceptance/release work and
  open product decisions; update it from source before starting M4 or claiming readiness.
- `screenshots/` contains generated device captures owned by the screenshot workflow.

# Local Contracts
- Use the Ky suite's mdBook documentation format (owner, 2026-11-16), following the chapter structure in `kypost-site/docs/`.
- Describe current behavior from source and UI strings. Mark planned capabilities explicitly.
- Keep user tasks, security/privacy limits, contributor setup and test commands documented.
- Commit manual sources; build output in `book/` is generated and ignored.
- The root `SECURITY.md`, `CONTRIBUTING.md` and `CHANGELOG.md` remain authoritative for reporting, contribution policy and release notes; link to them rather than duplicating their policies.

# Work Guidance
- Add every manual chapter to `src/SUMMARY.md` and link related tasks.
- Keep instructions usable without engineering knowledge; put wire and build details in their own chapters.

# Verification
- `mdbook build docs`
- `python3 scripts/check-docs.py` checks generated internal links and anchors.
- `.github/workflows/docs.yml` builds and checks the book, then uploads it as an artifact.

# Child DOX Index
None.
