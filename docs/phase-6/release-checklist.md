# Release checklist — ClearRead v1.0.0

Use before promoting a ClearRead build beyond local/dev.

## Pre-flight

- [x] `./gradlew test` (backend) green (ITs skip without Docker)
- [x] `cd frontend && npm test && npx tsc --noEmit` green
- [ ] `./scripts/start.sh` brings API + web healthy (environment-dependent)
- [ ] `./scripts/check.sh` all OK (environment-dependent)
- [ ] `cd frontend && npm run test:e2e` against live stack (or documented skip)
- [ ] Demo IdP credentials replaced for non-dev
- [ ] AI disabled unless policy reviewed
- [ ] HTTPS + HSTS at edge
- [ ] Image digests pinned for production registries
- [x] Version `1.0.0` in backend, frontend, OpenAPI, CHANGELOG, README badge

## Product acceptance (§25) — carried from acceptance COMPLETE

- [x] Upload → reader
- [x] URL import (SSRF suite green)
- [x] Paste and save
- [x] Original download checksum match
- [x] Provenance / inferred distinguishable
- [x] Structured formats render (JSON/CSV)
- [x] Long docs usable (progressive reveal)
- [x] Preferences persist
- [x] Search → `?q=` find in reader
- [x] Cross-tenant denied (tests)
- [x] XSS mitigations (no unsafe HTML; CSP-related headers)
- [x] Failed → diagnostics / reprocess
- [x] Share expire / revoke / permission flags
- [x] Export MD / HTML / PDF / original (generated labeled)
- [x] AI optional + governed
- [x] Keyboard primary paths
- [x] Backup / restore scripts + runbook (+ `--with-objects`)
- [x] Operator runbook + go-live decision reviewed

## Final v1.0 dispositions

- [x] AsciiDoc / RST: final-wontfix honesty stubs
- [x] Mermaid: final non-executing safe path
- [x] Playwright suite present + documented
- [x] Object-store backup helper or documented limitation (helper shipped)
- [x] Windows + Unix scripts matrix documented

## Sign-off

Tag **`v1.0.0`** on `main` after merge. Record SHA in project-store `docs/final-release.md`.
