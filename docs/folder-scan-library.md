# Local folder scan library (v1.2.0)

ClearRead can list readable files from **user-chosen preferred folders** and open a selected file into the normal ingest → reader flow. Browsers cannot silently scan arbitrary disk paths; this feature uses web platform APIs on the client.

## Behavior

1. **Settings → Local folder library** — add / remove preferred folders; toggle the feature; **Re-scan**.
2. **Library** — left-hand vertical menu lists discovered files grouped by folder. Selecting a file uploads it through `POST /v1/documents/upload` (soft-dedup preserved) and opens the reader.
3. Scans are **list-only** until the user opens a file. Default size filter: **10 MiB** (same as API `clearread.limits.max-upload-bytes`).
4. Extensions / MIME types come from `GET /v1/capabilities` (`ingest: "supported"` only). Unsupported binaries are skipped.

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
3. Confirm the folder appears with a file count and last scan time. Inaccessible paths show an error badge.
4. Open **Library** — left menu lists files under that folder. Use **Re-scan** and confirm last scan time updates.
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

## Server scan roots (optional / not in v1.2.0 UI)

The API container cannot see the host PC unless you mount volumes. Example Compose sketch (commented in `infra/compose.yaml`):

```yaml
# api:
#   volumes:
#     - ./notes:/data/scan-roots/notes:ro
```

Do **not** claim Docker can browse the whole machine without mounts. Prefer the browser library for local desktops.

## Related code

- `frontend/src/library/*` — filter, IndexedDB, FS Access, scan, open
- `frontend/src/pages/SettingsPage.tsx` — preferred folders UI
- `frontend/src/pages/LibraryPage.tsx` — left file menu
- Unit tests: `frontend/src/library/fileFilter.test.ts`
