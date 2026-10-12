# User guide (ClearRead v1.0)

ClearRead turns Markdown and other text-based files into a calm, structured reading experience while preserving the original bytes.

## Sign in

1. Open the web UI (Compose default: http://localhost:5173).
2. Sign in with your organization IdP. Local demo: `demo` / `demo`.

## Paste → read

1. Home → **Paste**.
2. Choose a supported format (see [format support](../format-support.md)).
3. Paste content → **Read document**.
4. Wait until status is ready; the reader opens automatically.

## Upload → read

1. Home → **Upload**.
2. Choose a text-based file whose extension is listed as ingestible.
3. **Upload & read**. Unsupported formats (including AsciiDoc/RST stubs) are rejected honestly.

## Reader basics

- Themes: **Day / Sepia / Night / System** (toolbar or keys `1` / `2` / `3`).
- Outline / TOC, find (`/`), original vs compare views (`o` / `c` / `r`).
- Processing notes appear when the pipeline recorded warnings.
- Mermaid fences show source + a text outline — diagrams are never executed.

## Library & search

- **Library** lists workspace documents; search deep-links into reader find via `?q=`.

## Share

1. Open a document → **Share / preview**.
2. Set expiry and permission flags → **Create share + preview**.
3. Copy the one-time token / preview URL.
4. **Revoke** active links when finished.

## Export

From the reader toolbar: Export Markdown / HTML / PDF. These are **generated** artifacts. Use **Download original** for the immutable source.

## Optional AI

AI enrichment is off by default and never required for reading. Org admins enable it under Admin when policy allows.
