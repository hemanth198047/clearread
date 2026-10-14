# Security overview (ClearRead v1.0)

## Principles

- Preserve originals (immutable object keys + checksums); derived ClearDoc is separate.
- Capability registry honesty — no fake format support.
- Core path never requires AI; AI is optional, gated, and auditable.
- Multi-tenant isolation via `tenant_id` and object-key prefixes.

## AuthN / AuthZ

- OIDC resource server (JWT). SPA uses PKCE.
- Cross-tenant document access denied (integration-tested when Docker is available).

## Ingest & SSRF

- URL fetch: scheme/port allowlists, redirect limits, private/metadata/`*.internal` denials.
- Ordinary web pages rejected for URL ingest (no silent scraping).
- Upload size and nesting limits in `clearread.limits.*`.

## XSS / content safety

- HTML ingest sanitized; exports are sanitized HTML or constrained text PDF.
- **Mermaid is never executed** in the browser or server — source + text outline only.
- Security headers filter (CSP-related hardening, frame deny, referrer policy). See `SecurityHeadersFilter` and [phase-6/threat-model-delta.md](../phase-6/threat-model-delta.md).

## Sharing

- Tokenized share + preview links with expiry, revoke, view caps, and permission flags.

## Ops

- Rate limiting per instance (`ApiRateLimitFilter`): authenticated reads exempt; local Compose `CLEARREAD_API_RPM=0`; public edges use `CLEARREAD_PUBLIC_API_RPM` (default 120); prod profile defaults authenticated RPM to 600.
- Prometheus authenticated; isolate at the network layer in production.
- Backup both Postgres and object store ([backup-restore](./backup-restore.md)).

## Final v1.0 non-goals (attack-surface conscious)

No Office/PDF ingest engines, no OCR, no live Mermaid renderer, no Asciidoctor/docutils subprocess parsers.

Phase 0 threat model lives in the project agent store; delta for hardening: [threat-model-delta.md](../phase-6/threat-model-delta.md).
