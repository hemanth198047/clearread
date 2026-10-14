# User guide (ClearRead v1.2)

ClearRead turns Markdown and other text-based files into a calm, structured reading experience while preserving the original bytes.

## Sign in / Create account

1. Open the web UI (Compose default: http://localhost:5173).
2. **Sign in** — existing Keycloak / org IdP account.
3. **Create account** — local Compose enables Keycloak self-registration; you land in the Demo Workspace as an author after first login.

### Local demo accounts

Shown on the landing page when `CLEARREAD_SHOW_DEMO_ACCOUNTS=true` (default for Compose):

| Username | Password | Role |
| --- | --- | --- |
| `admin` | `admin` | Workspace admin |
| `user` | `user` | Standard member (author) |
| `demo` | `demo` | Legacy admin (compat) |

Windows: `scripts\start.bat` prints the same credentials when the stack is ready.

Production / enterprise: access is via your organization IdP (OIDC SSO or invite). Admins can also create an invite link under Admin → Invite user (copy link; email is not sent by ClearRead).

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
- The **left file menu** lists readable files from preferred local folders (see below). Opening a file uploads it through the normal ingest pipeline (soft-dedup applies).

## Preferred local folders (v1.2.2)

Browse folders on your machine without auto-uploading everything:

1. **Settings** → enable **Local folder library** → **Add folder…** (Chrome/Edge recommended).
2. ClearRead **scans immediately** and **recursively through all subfolders** (**Scanning…**, then last-scan time). Capability-supported text formats under **10 MiB** are listed (nothing is uploaded yet). No app restart required.
3. The **Local files** left menu appears on **Library** and **Ingest** after folders are configured. Choose a file → reader (API required to open; the list still works if the API is down).
4. Use **Re-scan** to refresh; inaccessible folders show an error. Safari/Firefox may require re-adding the folder after reload.

Details and browser limits: [folder-scan-library.md](../folder-scan-library.md).

## Share

1. Open a document → **Share / preview**.
2. Set expiry and permission flags → **Create share + preview**.
3. Copy the one-time token / preview URL.
4. **Revoke** active links when finished.

## Export

Reader toolbar → Export Markdown / HTML / PDF (generated artifacts; originals stay separate).
