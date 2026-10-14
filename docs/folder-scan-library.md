# Local folder scan library (v1.2.4)

ClearRead can list readable files from **user-chosen preferred folders** (recursively through subfolders) and open a selected file into the normal ingest → reader flow. Browsers cannot silently scan arbitrary disk paths; this feature uses web platform APIs on the client.

## Behavior

1. **Settings → Local folder library** — add / remove preferred folders; toggle the feature; **Re-scan**.
2. **Scan on select** — as soon as **Add folder…** succeeds (File System Access picker or `webkitdirectory` fallback), ClearRead scans that folder **recursively to leaf directories**, shows **Scanning…**, updates **Last scan** time, and refreshes the left file menu. You do **not** need to restart the app.
3. **Startup auto-scan** — a shared app-shell library provider re-scans preferred folders once on load (same recursive walk).
4. **Left vertical file menu** — shown on **Library** and on **Ingest/Home** when folders are configured (or a scan is in progress). Files are grouped by preferred folder and subdirectory path. Selecting a file uploads it through `POST /v1/documents/upload` (soft-dedup preserved) and opens the reader.
5. Scans are **list-only** until the user opens a file. Default size filter: **10 MiB**. Walk limits: **max depth 20**, **max 5000 files** per preferred root; skips `node_modules`, `.git`, `dist`, `build`, and other common ignore dirs.
6. Extensions / MIME types come from `GET /v1/capabilities` when available. If the API is unreachable or rate-limited, ClearRead uses a **client-side fallback** extension list so the local menu still populates. Opening a file still requires a reachable API (clear error message).

## API down / Recent documents / rate limits

- Local file menu stays visible when the API is down — do not hide the rail based on API health.
- **Recent** on Home uses a short TTL cache, in-flight dedupe, and exponential backoff on HTTP 429 so remounts / StrictMode do not storm `GET /v1/documents`.
- Server rate limit skips cheap bootstrap GETs (`/v1/capabilities`, `/v1/branding/*`, health) **and authenticated read-heavy GETs** (`/v1/library`, `/v1/documents/**`, `/v1/search`, …) so Library browse and document open after a large folder scan do not 429.
- **Local Compose default:** `CLEARREAD_API_RPM=0` (authenticated limit disabled). Public edges keep `CLEARREAD_PUBLIC_API_RPM` (default **120**). Production: set `CLEARREAD_API_RPM=600` and/or `SPRING_PROFILES_ACTIVE=prod`.

## Browser support

| Browser | Preferred folders | Persistence |
| --- | --- | --- |
| **Chrome / Edge** (desktop) | File System Access API (`showDirectoryPicker`) | Directory handles in IndexedDB; re-scan after reload with permission prompt as needed |
| **Safari / Firefox** | `<input type="file" webkitdirectory>` fallback | File list metadata only; **re-add the folder** after reload to refresh / open files |
| Mobile browsers | Limited / often unavailable | Use paste or single-file upload on Ingest |

## Windows verify (Chrome)

Assume checkout at `D:\ProjectsPortfolio\clearread` and stack via `scripts\start.bat`.

1. Open http://localhost:5173 → sign in (`user` / `user` or `admin` / `admin`).
2. **Settings** → ensure **Enable local folder library** is on → **Add folder…** → pick a folder that has nested subfolders with `.md` / `.txt` / `.json` (etc.).
3. Confirm **Scanning…**, then file count + **Last scan** (no restart). Nested files from subfolders appear.
4. Open **Ingest** — left **Local files** rail lists the same scan results even if the API chip shows Down.
5. Open **Library** — left menu lists files grouped by folder / path → open one → reader (needs API).
6. With API up, confirm **Recent** does not stick on “Too many requests” after normal navigation; refresh still works after backoff/cache.
7. Remove the folder in Settings — it disappears from the left menu after refresh/rescan.

## Empty states

- No folders configured → Settings CTA.
- Permission denied / missing handle → inaccessible badge + error text; re-add folder.
- No matching files (including after recursive walk) → **No readable files found**.
- Scan in progress → **Scanning…** / **Scan in progress…**.

## Persistence

- IndexedDB database `clearread-local-library`: folder records, directory handles (when supported), scanned file metadata, last scan timestamp.
- Feature flag: `localStorage` key `clearread.localFolderLibraryEnabled` (default on).
- Folder handles never leave the browser.
- Cross-view refresh: shared `LocalLibraryProvider` + `clearread:local-library-changed` window event after add / remove / re-scan.

## Server scan roots (optional / not in v1.2.x UI)

The API container cannot see the host PC unless you mount volumes. Example Compose sketch (commented in `infra/compose.yaml`):

```yaml
# api:
#   volumes:
#     - ./notes:/data/scan-roots/notes:ro
```

Do **not** claim Docker can browse the whole machine without mounts. Prefer the browser library for local desktops.

## Related code

- `frontend/src/library/*` — filter, walk limits, IndexedDB, FS Access, scan, open, shared provider
- `frontend/src/api/recentDocuments.ts` — Recent list cache / 429 backoff
- `frontend/src/pages/SettingsPage.tsx`, `LibraryPage.tsx`, `HomePage.tsx` — UI surfaces
- Unit tests: `folderWalk.test.ts`, `fileFilter.test.ts`, `scanOnSelect.test.ts`, `recentDocuments.test.ts`
