# Go-live decision record

**Product:** ClearRead (temporary name; branding configurable)  
**Version:** **v1.0.0 FINAL**  
**Date:** 2026-10-12  
**Scope:** Phases 1–6 + acceptance + post-acceptance + final release packaging on `main`

## Decision

**Unconditional go-live approved for self-hosted / staging-equivalent ClearRead v1.0.0 deployments** that:

1. Replace demo IdP credentials and disable demo users  
2. Keep AI disabled unless org policy + provider config are reviewed  
3. Enable HTTPS termination with HSTS at the edge  
4. Schedule Postgres + object-store backups ([backup-restore](../guides/backup-restore.md); `--with-objects` or manual sync)  
5. Accept residual risks in [`threat-model-delta.md`](./threat-model-delta.md) and final wontfix listed in [format-support.md](../format-support.md)

## Evidence

- Automated tests: unit + Testcontainers integration (authz isolation, SSRF, Phase 4 org, Phase 5 AI + injection, Phase 6 headers/rate-limit); ITs skip cleanly without Docker
- Frontend vitest + Playwright smoke suite (run against live Compose; see `frontend/e2e/README.md`)
- Capability registry honesty; originals checksummed; exports labeled generated
- Operator runbook + backup/restore scripts (Postgres + optional S3 sync)
- User/admin/deployment/security/format guides under `docs/`

## Residual risks (accepted for v1.0)

- No dedicated external pen-test report in-repo
- Prometheus endpoint authenticated but not network-isolated by default
- Rate limiting is per-instance
- AsciiDoc/RST parsers and live Mermaid rendering intentionally omitted
- Playwright requires a running stack (skipped when Docker/API unavailable)

## Sign-off

Recorded for **FINAL v1.0.0** under standing user preference to merge without approval gates. Tag: `v1.0.0`.
