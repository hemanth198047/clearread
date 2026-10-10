# Go-live decision record

**Product:** ClearRead (temporary name; branding configurable)  
**Date:** 2026-10-10  
**Scope:** Phases 1–6 on `main` after merge of hardening PR

## Decision

**Conditional go-live approved for self-hosted / staging-equivalent deployments** that:

1. Replace demo IdP credentials and disable demo users  
2. Keep AI disabled unless org policy + provider config are reviewed  
3. Enable HTTPS termination with HSTS at the edge  
4. Schedule Postgres + object-store backups using the operator runbook  
5. Accept residual risks in [`threat-model-delta.md`](./threat-model-delta.md)

## Evidence

- Automated tests: unit + Testcontainers integration (authz isolation, SSRF, Phase 4 org, Phase 5 AI + injection, Phase 6 headers/rate-limit)
- Capability registry honesty; originals checksummed; exports labeled generated
- Operator runbook + backup/restore scripts

## Residual risks (accepted)

- No dedicated external pen-test report in-repo
- Prometheus endpoint authenticated but not network-isolated by default
- Rate limiting is per-instance

## Sign-off

Recorded by implementation agent under standing user preference to complete Phase 6 and merge without inter-phase approval gates. Operators should re-confirm before production traffic.
