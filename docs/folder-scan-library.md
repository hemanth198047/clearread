# Local folder scan library (v1.2.1)

ClearRead can list readable files from **user-chosen preferred folders** and open a selected file into the normal ingest → reader flow. Browsers cannot silently scan arbitrary disk paths; this feature uses web platform APIs on the client.

## Behavior

1. **Settings → Local folder library** — add / remove preferred folders; toggle the feature; **Re-scan**.
2. **Scan on select (v1.2.1)** — as soon as **Add folder…** succeeds (File System Access picker or `webkitdirectory` fallback), ClearRead scans that folder immediately, shows **Scanning…**, updates **Last scan** time, and refreshes the Library left file menu. You do **not** need to restart the app or wait for the next Library mount.
3. **Startup / Library load** — when the Library page loads with the feature enabled, preferred folders are still re-scanned (same as v1.2.0).
4. **Library** — left-hand vertical menu lists discovered files grouped by folder. Selecting a file uploads it through `POST /v1/documents/upload` (soft-dedup preserved) and opens the reader.
5. Scans are **list-only** until the user opens a file. Default size filter: **10 MiB** (same as API `clearread.limits.max-upload-bytes`).
6. Extensions / MIME types come from `GET /v1/capabilities` (`ingest: "supported"` only). Unsupported binaries are skipped. Post-select scans wait for (or fetch) capabilities so the filter index is not empty.

## Browser support

| Browser | Preferred folders | Persistence |
| --- | --- | --- |
| **Chrome / Edge** (desktop) | File System Access API (`showDirectoryPicker`) | Directory handles in IndexedDB; re-scan after reload with permission prompt as needed |
| **Safari / Firefox** | `<input type="file" webkitdirectory>` fallback | File list metadata only; **re-add the folder** after reload to refresh / open files |
| Mobile browsers | Limited / often unavailable | Use paste or single-file upload on Ingest |

## Windows verify (Chrome)

Assume checkout at `D:\ProjectsPortfolio\clearread` and stack via `scripts\start.bat`.

1. Open http://localhost:5173 → sign in (`user` / `user` or `admin` / `admin`).
2. **Settings** → ensure **Enable local folder library** is on → **Add folder…** → pick a folder with `.md` / `.txt` / `.json` (etc.).
3. Confirm **Scanning…** appears right away, then the folder shows a file count and **Last scan** time (no app restart). Inaccessible / permission errors show in the status alert and on the folder row.
4. Open **Library** — left menu already lists files under that folder (also refreshes if Settings broadcast an update). Use **Re-scan** and confirm last scan time updates.
5. Click a file — status shows upload/processing, then the reader opens (identical content reuses soft-dedup).
6. Remove the folder in Settings — it disappears from the left menu after refresh/rescan.

## Empty states

- No folders configured → Settings CTA.
- Permission denied / missing handle → inaccessible badge + error text; re-add folder.
- No matching files → “No matching files” under the folder group.

## Persistence

- IndexedDB database `clearread-local-library`: folder records, directory handles (when supported), scanned file metadata, last scan timestamp.
- Feature flag: `localStorage` key `clearread.localFolderLibraryEnabled` (default on).
- Folder handles never leave the browser.
- Cross-view refresh: `clearread:local-library-changed` window event after add / remove / re-scan.

## Server scan roots (optional / not in v1.2.x UI)

The API container cannot see the host PC unless you mount volumes. Example Compose sketch (commented in `infra/compose.yaml`):

```yaml
# api:
#   volumes:
#     - ./notes:/data/scan-roots/notes:ro
```

Do **not** claim Docker can browse the whole machine without mounts. Prefer the browser library for local desktops.

## Related code

- `frontend/src/library/*` — filter, IndexedDB, FS Access, scan, open, scan-on-select helpers
- `frontend/src/pages/SettingsPage.tsx` — preferred folders UI
- `frontend/src/pages/LibraryPage.tsx` — left file menu
- Unit tests: `frontend/src/library/fileFilter.test.ts`, `frontend/src/library/scanOnSelect.test.ts`
