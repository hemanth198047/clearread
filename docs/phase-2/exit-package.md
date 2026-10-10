# Phase 2 Exit Package

**Status:** Complete — awaiting approval before Phase 3  
**PR:** https://github.com/hemanth198047/mdbeautifier/pull/1  
**Branch:** `cursor/phase-1-vertical-slice-f2a6`

## 1. What works

- Day / Sepia / Night / System reader themes via CSS variables (defaults per D-THEME-1)
- Collapsible TOC with active-section highlighting
- Segmentation: Conservative (default) / Balanced / Detailed — **view-layer only**, originals + ClearDoc unchanged
- Original source viewer and side-by-side Original vs ClearDoc comparison
- Preferences: font family/size, line height, reading width, paragraph/heading spacing, code font size, line numbers, section collapsing
- Find-in-document with match navigation; reading progress; scroll restoration; deep links (`#nodeId`)
- Keyboard shortcuts + in-app reference (`?`)
- Responsive layout; skip link; focus-visible; `prefers-reduced-motion` respected
- Large docs: progressive block reveal (“show remaining”)
- Preferences persist in `localStorage` and, when signed in, `GET/PUT /v1/me/reader-preferences`

## 2. Known limitations

- Theme demo PNGs under `media/phase-2/` are high-fidelity static shells driven by live ClearDoc JSON (same tokens/IR as the SPA); interactive SPA chrome matches those controls.
- Full axe CI gate and virtualized windowing library deferred as stretch — progressive chunking used instead.
- Find highlights rebuild with React text marking (adequate for Phase 2 sizes).

## 3. Checks run

| Check | Result |
| --- | --- |
| `cd backend && ./gradlew test` | **PASS** |
| `cd frontend && npm test` | **PASS** (5 tests: ClearDocView, themes, segmentation) |
| `cd frontend && npx tsc --noEmit` | **PASS** |
| Live `PUT/GET /v1/me/reader-preferences` | **PASS** (sepia prefs persisted) |
| API health + docs list | **PASS** |

## 4. Local run

```bash
./scripts/start.sh
# http://localhost:5173 — demo / demo
# Open a document → use Day/Sepia/Night, Contents, Original, Compare, Preferences, Find (/)
./scripts/stop.sh
```

## 5. Demo media

- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-2/reader-day.png`
- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-2/reader-sepia.png`
- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-2/reader-night.png`
- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-2/reader-compare.png`
- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-2/spa-home.png`

## 6. Docs

- Store: `docs/phase-1/approval.md`, `docs/phase-2/theme-decision.md`, `preferences.md`, decisions log, project-context
- Repo: `docs/phase-2/status.md`, this exit package

## 7. Stop

**Phase 3 not started.**
