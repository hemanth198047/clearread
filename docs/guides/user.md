# User guide (ClearRead v1.0)

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

## Share

1. Open a document → **Share / preview**.
2. Set expiry and permission flags → **Create share + preview**.
3. Copy the one-time token / preview URL.
4. **Revoke** active links when finished.

## Export

Reader toolbar → Export Markdown / HTML / PDF (generated artifacts; originals stay separate).
