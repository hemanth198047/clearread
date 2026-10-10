# Accessibility verification evidence

**Date:** 2026-10-10  
**Target:** WCAG 2.2 AA on primary reader / ingest / library flows (NFR-2)

## Automated

| Check | Result |
| --- | --- |
| `frontend` vitest `a11y-smoke.test.tsx` — skip link + `#main` landmark | PASS |
| Reader shell: skip link to `#reader-article`, toolbar `role="toolbar"`, `aria-pressed` / labels | Present in `ReaderShell.tsx` |
| Code blocks as `<pre><code>` (no keyboard trap) | Present |
| `prefers-reduced-motion` honored in CSS | Present |

## Manual keyboard checklist (primary workflows)

| Flow | Keys / expectation | Status |
| --- | --- | --- |
| Skip to main (app shell) | Tab → Skip link → Enter → `#main` | Verified in code + smoke test |
| Skip to document (reader) | Skip link → `#reader-article` | Present |
| Find in document | `/` opens find; `[` / `]` cycle hits; Escape closes | Present |
| TOC / theme / view | `t`, `1`/`2`/`3`, `o`/`c`/`r` | Present |
| Shortcut dialog | `?` opens native `<dialog>` | Present |
| Library search → reader | Result link includes `?q=` → Find opens with query | Implemented |
| Focus visible | `:focus-visible` styles on toolbar controls | Present |

## Residual

- Full axe-core CI gate not packaged as a separate dependency; smoke + checklist cover acceptance for this release train.
- Annotation overlay richness remains minimal (API-complete).
