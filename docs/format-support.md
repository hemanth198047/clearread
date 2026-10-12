# Format support (ClearRead v1.0)

**Source of truth:** `GET /v1/capabilities` and `backend/src/main/resources/capabilities/registry.json`.

Unsupported means unsupported — ClearRead will not silently pretend a format is fully readable.

## Ingestible (supported)

| Format | Extensions (typical) | Notes |
| --- | --- | --- |
| Markdown (CommonMark + GFM) | `.md`, `.markdown` | GFM tables → `dataTable`. Mermaid fences: source + text outline only |
| Plain text | `.txt`, `.text` | Heuristic structure |
| JSON | `.json` | Tree + formatted source |
| YAML | `.yaml`, `.yml` | Tree + formatted source |
| XML | `.xml` | Tree |
| CSV / TSV | `.csv`, `.tsv` | Tabular → `dataTable` (row caps apply) |
| Log | common log extensions | Line-oriented |
| Config | `.ini`, `.conf`, `.properties`, … | Key/value oriented |
| Source code | common language extensions | Fenced source presentation |
| HTML (sanitized file) | `.html`, `.htm` | Upload sanitize only; URL ingest rejects ordinary pages |

## Partial / constrained

| Feature | Status | Rationale |
| --- | --- | --- |
| **Mermaid diagrams** | Partial | Non-executing safe path: preserve fenced source + size-limited text outline. **No live renderer in v1.0** (XSS / engine surface). |
| GFM tables | Partial | Pipe tables only; no rowspan/colspan |
| PDF **export** | Constrained generated artifact | ClearDoc → plain-text PDF; not a print/layout engine |
| URL ingest | Supported with SSRF guards | Private/metadata/`*.internal` denied; web pages rejected |

## Unsupported honesty stubs (final v1.0 non-goals)

| Format | Behavior | Why not in v1.0 |
| --- | --- | --- |
| **AsciiDoc** (`.adoc`, …) | Detected → **415** | AsciidoctorJ includes/passthrough expand path + XSS surface; no locked-down ClearDoc mapping shipped |
| **reStructuredText** (`.rst`, …) | Detected → **415** | No mature pure-JVM parser; Python/docutils subprocess breaks the Compose simplicity bar |

Convert AsciiDoc/RST to Markdown (or paste plain text) before ingest.

## Out of v1.0 (Phase 0 hard non-goals)

Office ingest (DOCX/PPTX/XLSX), OCR, PDF **ingest**, collaborative realtime editing, Math/KaTeX.

## Exports (generated artifacts)

All exports are `artifactKind=generated`. Download **original** for byte-identical source.

| Target | Converter id |
| --- | --- |
| Markdown | `cleardoc-to-markdown` |
| Sanitized HTML | `cleardoc-to-html-sanitized` |
| Constrained text PDF | `cleardoc-to-pdf-constrained` |
