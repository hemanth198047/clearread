# Phase 5 Exit Package — Optional AI enrichment

**Status:** Complete  
**Branch:** `cursor/phase-5-6-hardening-f2a6`  
**Prior merged:** https://github.com/hemanth198047/mdbeautifier/pull/1 → `main` @ `74eb3c5`

## 1. What works

- AI **disabled by default**; core reader works without AI; enrichment failures never mark documents failed
- Provider-neutral SPI: `mock-local` (deterministic, always available), `ollama` (local, config-gated), `external` (seam, requires allow-external + local-only off)
- Org policy: `aiEnabled`, `aiLocalOnly`, `aiAllowExternal`, `aiProviderId`, daily quota, max retries (Admin UI + `PUT /v1/tenants/{id}/settings`)
- Derived artifacts only: headings, segmentation, summary/key points, glossary — stored in `ai_enrichments`, not silent ClearDoc mutation
- Source-linked review UI on Reader: before/after compare; accept/reject required before display overlay
- Explicit `AI-generated` labels; schema validation; missing/duplicate coverage warnings
- Source-range / blockNodeId excerpts preferred over full-document reproduction
- Usage metering, timeouts, retry budgets, per-tenant daily quotas
- Prompt-template versioning (`ai_prompt_templates`); provider/model metadata; audit events
- Cache keys = document revision + enrichment kind + config hash
- Document content **not** logged by default (`clearread.ai.log-document-content=false`)
- Prompt-injection resistance: untrusted wrapping, no tools/credentials on worker, response abuse filter, unit + integration coverage
- Day/Sepia/Night and prior phases retained

## 2. Limitations

- Real Ollama/external calls require env enablement; demos use `mock-local`
- Accepting a proposal applies a **display overlay** only (does not rewrite stored ClearDoc) — intentional for Phase 5 safety
- External provider is a configurable HTTP seam, not a full vendor SDK

## 3. Checks run

| Check | Result |
| --- | --- |
| `./gradlew test --tests 'com.clearread.ai.*' --tests 'com.clearread.api.Phase5AiIntegrationTest'` | **PASS** |
| Prompt-injection unit tests (`PromptInjectionGuardTest`) | **PASS** |
| Frontend `EnrichmentReview` TypeScript (tsc with suite) | run in Phase 5+6 batch |

## 4. Local run / enable AI

```bash
./scripts/start.sh
# Admin → Optional AI enrichment → enable AI, provider mock-local, save
# Open a Ready document → AI enrichment review → request Summary / Headings → Accept

# Optional real Ollama:
export CLEARREAD_AI_OLLAMA_ENABLED=true
export CLEARREAD_AI_OLLAMA_URL=http://127.0.0.1:11434
export CLEARREAD_AI_OLLAMA_MODEL=llama3.2
# Admin: provider=ollama, local-only=true, allow-external=false

# Optional external seam:
export CLEARREAD_AI_EXTERNAL_ENABLED=true
export CLEARREAD_AI_EXTERNAL_URL=https://your-gateway.example/v1/enrich
export CLEARREAD_AI_EXTERNAL_API_KEY=...
# Admin: provider=external, local-only=false, allow-external=true
```

## 5. Demo media

- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-5/enrichment-review.png`
- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-5/admin-ai-settings.png`

## 6. Stop / continue

Standing preference: **no approval gate**. Proceed immediately to Phase 6.
