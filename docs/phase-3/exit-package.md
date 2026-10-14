# Phase 3 Exit Package

**Status:** Complete — awaiting approval before Phase 4  
**PR:** https://github.com/hemanth198047/clearread/pull/1  
**Branch:** `cursor/phase-1-vertical-slice-f2a6`

## 1. What works

- **SSRF-hardened URL ingest** (`POST /v1/documents/from-url`): http/https only; reject userinfo; block loopback/private/link-local/multicast/metadata; validate all resolved addresses; revalidate redirects; limit redirects/ports; DNS pin + connection match; TLS hostname verification (HTTPS endpoint ID + SNI); connect/read/total/streamed/decompressed limits; never forward cookies/credentials; redact sensitive query params in logs. Private-network ingest **disabled by default** (`clearread.fetch.allow-private-network=false`).
- Ordinary **web pages are not scraped** — clear `WEB_PAGE` error explains to use a raw file URL or paste/upload.
- **Durable DB jobs**: `SKIP LOCKED` leasing, fencing via `lease_token`, retries with exponential backoff + jitter, per-tenant concurrency, cancel (pending → cancelled; leased cancel_requested), idempotency keys, recovery of expired leases.
- **Worker placement (D-JOB-2):** in-process scheduled `JobWorker` inside the API deployable (long-running work off request threads). Separate worker process remains an evolution seam.
- **Formats** via capability registry + adapters: JSON (collapsible tree + formatted source), YAML (SnakeYAML `SafeConstructor`), XML (XXE disabled), CSV/TSV (quoted/multiline table), log, config/env, source-code (never execute), HTML sanitized file ingest.
- **Diagnostics** `GET /v1/documents/{id}/diagnostics` and **reprocess** `POST /v1/documents/{id}/reprocess` (new derived revision; originals unchanged).
- Job status/cancel: `GET/POST /v1/jobs/{id}` / `.../cancel`.
- Phase 2 Day/Sepia/Night reader UX retained; structured `dataTable` / `treeNode` / `formattedSource` views.

## 2. Known limitations

- Demo PNGs under store `media/phase-3/` are high-fidelity shells matching product tokens/layout (live SSRF suite is automated; public URL fetch depends on egress).
- HTML URL ingest of ordinary pages is intentionally rejected (no silent scrape); uploaded `.html` files use sanitized adapter.
- Full virtualized CSV windowing uses progressive row cap (200 visible + server max rows); not a separate virtualization library.
- In-process workers share API blast radius (accepted residual risk from Phase 0).

## 3. Checks run

| Check | Result |
| --- | --- |
| `cd backend && ./gradlew test` | **PASS** (SSRF policy, redaction, web-page detection, XXE/YAML/nesting/CSV, isolation) |
| `cd frontend && npm test` | **PASS** (5 tests) |
| `cd frontend && npx tsc --noEmit` | **PASS** |

## 4. Local run

```bash
./scripts/start.sh
# http://localhost:5173 — demo / demo
# Paste/upload JSON/CSV/… or From URL with a direct public file URL
# Reader: Day/Sepia/Night, Reprocess, structured views
./scripts/stop.sh
```

Private fetch for lab only: `CLEARREAD_FETCH_ALLOW_PRIVATE=true` (not default).

## 5. Demo media

- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-3/url-ingest.png`
- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-3/json-reader.png`
- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-3/csv-reader.png`

## 6. Docs

- Store: Phase 2 approval, D-PHASE-2-EXIT, D-JOB-2, project-context, `docs/phase-3/*`
- Repo: this exit package + `docs/phase-3/status.md`

## 7. Stop

**Phase 4 not started.**
