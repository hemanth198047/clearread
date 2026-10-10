# Threat model delta — Phases 3–6

Extends store Phase 0 threat model.

## New / updated assets

| Asset | Notes |
| --- | --- |
| AI prompts / enrichments | Derived; may contain excerpted untrusted content |
| AI usage / quotas | Abuse metering |
| Share / preview token hashes | Bearer capability; hash-only at rest |
| Exports | Generated artifacts, labeled |

## AI enrichment (Phase 5) — T-AI

| Threat | Mitigation |
| --- | --- |
| Prompt injection in uploaded docs | Untrusted excerpt wrapping; system guard template; no tools/credentials; worker cannot fetch URLs / change permissions / modify unrelated docs |
| Data exfil via external model | Default local-only; external requires explicit org allow; kill switch |
| Silent source mutation | Derived `ai_enrichments` only; review/accept before display overlay |
| Secret leakage in logs | `log-document-content=false`; audit without body |
| Quota exhaustion | Daily per-tenant quota + job retry budget + timeouts |

## Hardening (Phase 6)

| Threat | Mitigation |
| --- | --- |
| Clickjacking / MIME sniff | Frame deny, nosniff, referrer policy |
| Request floods | Per-IP RPM limit; existing per-tenant job concurrency |
| Blind observability | Health probes + Prometheus metrics |
| Backup loss | `backup.sh` / `restore.sh` + runbook |

## Residual risks

- In-process rate limit bypass via many instances without shared store
- Supply-chain: production should pin image digests and verify SBOM
- Full adversarial pen-test still recommended before high-sensitivity deploy
