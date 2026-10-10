# Release checklist

Use before promoting a ClearRead build beyond local/dev.

## Pre-flight

- [ ] `./gradlew test` (backend) green
- [ ] `cd frontend && npm test && npx tsc --noEmit` green
- [ ] `./scripts/start.sh` brings API + web healthy
- [ ] `./scripts/check.sh` all OK
- [ ] Demo IdP credentials replaced for non-dev
- [ ] AI disabled unless policy reviewed
- [ ] HTTPS + HSTS at edge
- [ ] Image digests pinned for production registries

## Product acceptance (§25)

- [ ] Upload → reader
- [ ] URL import (SSRF suite green)
- [ ] Paste and save
- [ ] Original download checksum match
- [ ] Provenance / inferred distinguishable
- [ ] Structured formats render (JSON/CSV)
- [ ] Long docs usable (progressive reveal)
- [ ] Preferences persist
- [ ] Search → `?q=` find in reader
- [ ] Cross-tenant denied (tests)
- [ ] XSS mitigations (no unsafe HTML; CSP)
- [ ] Failed → diagnostics / reprocess
- [ ] Share expire / revoke / permission flags
- [ ] Export MD / HTML / PDF / original (generated labeled)
- [ ] AI optional + governed
- [ ] Keyboard primary paths
- [ ] Backup / restore scripts + runbook
- [ ] Operator runbook + go-live decision reviewed

## Sign-off

Record SHA, date, and operator in the acceptance report.
