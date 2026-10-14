# Performance notes (Phase 6 / acceptance)

## Documented limits

| Limit | Default | Source |
| --- | --- | --- |
| Upload / paste / URL body | 10 MiB | `clearread.limits.max-upload-bytes` / workspace `maxUploadBytes` |
| URL fetch time | policy-bound | `SafeHttpFetcher` |
| CSV rows shown in reader | 200 (UI) | `ClearDocView` |
| Progressive body reveal | 80 blocks / chunk | `ClearDocView` |
| Authenticated API rate limit | 0 (unlimited) local; 600 prod | `CLEARREAD_API_RPM` / `ApiRateLimitFilter` |
| Public (unauth) API rate limit | 120 req/min/IP | `CLEARREAD_PUBLIC_API_RPM` |
| Authenticated read GETs | Exempt | Library / documents / search |

## Budgets (from Phase 0)

See project store `docs/phase-0/performance-budgets.md`. Typical ClearDoc open should remain interactive; large docs use progressive reveal + outline-first navigation.

## Measured in this environment (2026-10-10)

| Check | Result |
| --- | --- |
| Backend unit + Testcontainers suite (`./gradlew test`) | PASS |
| Frontend `npm test` + `tsc --noEmit` | PASS |
| Full multi-GB soak / pen-test load | Not executed (residual risk accepted in go-live decision) |

## Operator guidance

- Keep uploads ≤ configured max; reject larger files at the edge if needed.
- Prefer outline + Find for long structured files; use Detailed segmentation only when needed.
- Scale API horizontally behind a shared rate-limit / WAF for production.
