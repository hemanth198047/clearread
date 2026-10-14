import { useRef } from "react";
import { Link } from "react-router-dom";
import { useSharedLocalLibrary } from "../library/LocalLibraryProvider";

function formatScanTime(iso: string | null): string {
  if (!iso) return "Never";
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
  } catch {
    return iso;
  }
}

export function SettingsPage() {
  const webkitInputRef = useRef<HTMLInputElement>(null);
  const lib = useSharedLocalLibrary();

  return (
    <section className="panel">
      <div className="page-header" style={{ border: 0, padding: 0, margin: 0 }}>
        <div>
          <p className="page-eyebrow">Workspace</p>
          <h1>Settings</h1>
          <p className="lede">
            Preferred local folders for the Local files menu (Library and Ingest). Scans walk all subfolders in your
            browser — nothing is uploaded until you open a file.
          </p>
        </div>
        <div className="page-header-actions">
          <Link className="button secondary" to="/library">
            Library
          </Link>
        </div>
      </div>

      <div className="settings-section">
        <h2>Local folder library</h2>
        <p className="muted">
          Chrome and Edge can remember directory handles (File System Access API). Safari and Firefox use a one-shot
          folder picker fallback — re-add folders after reload to refresh. Details:{" "}
          <code>docs/folder-scan-library.md</code> in the ClearRead repo.
        </p>

        <label className="check settings-enable">
          <input
            type="checkbox"
            checked={lib.enabled}
            onChange={(e) => void lib.setEnabledPref(e.target.checked)}
          />
          Enable local folder library
        </label>

        <div className="status-bar">
          <span className={`status-dot${lib.error ? " err" : lib.scanning ? " busy" : ""}`} />
          <span>
            {lib.scanning
              ? "Scanning…"
              : `${lib.folders.length} folder${lib.folders.length === 1 ? "" : "s"} · ${lib.files.length} readable file${lib.files.length === 1 ? "" : "s"}`}
          </span>
          <span className="muted">Last scan: {formatScanTime(lib.lastScanAt)}</span>
        </div>

        {lib.error && (
          <p className="alert" role="alert">
            {lib.error}
          </p>
        )}

        <div className="settings-actions">
          {lib.fsAccess ? (
            <button
              type="button"
              onClick={() => void lib.addFolderFsAccess()}
              disabled={!lib.enabled || lib.busyFolderId !== null || lib.scanning}
            >
              {lib.scanning && lib.busyFolderId === "new" ? "Scanning…" : "Add folder…"}
            </button>
          ) : null}
          {lib.webkitDir ? (
            <>
              <button
                type="button"
                className={lib.fsAccess ? "secondary" : undefined}
                disabled={!lib.enabled || lib.busyFolderId !== null || lib.scanning}
                onClick={() => webkitInputRef.current?.click()}
              >
                {lib.scanning && lib.busyFolderId === "new"
                  ? "Scanning…"
                  : lib.fsAccess
                    ? "Add folder (fallback)…"
                    : "Add folder…"}
              </button>
              <input
                ref={(el) => {
                  webkitInputRef.current = el;
                  if (el) el.setAttribute("webkitdirectory", "");
                }}
                type="file"
                className="visually-hidden"
                multiple
                onChange={(e) => {
                  const list = e.target.files;
                  if (list?.length) void lib.addFolderWebkit(list);
                  e.target.value = "";
                }}
              />
            </>
          ) : null}
          <button
            type="button"
            className="secondary"
            onClick={() => void lib.rescan()}
            disabled={!lib.enabled || lib.scanning || lib.folders.length === 0}
          >
            {lib.scanning ? "Re-scanning…" : "Re-scan folders"}
          </button>
        </div>

        {!lib.fsAccess && !lib.webkitDir ? (
          <p className="alert" role="alert">
            This browser cannot pick directories. Use Chrome or Edge on the desktop for preferred folders.
          </p>
        ) : null}

        {lib.folders.length === 0 ? (
          <div className="library-empty">
            <p>No preferred folders yet.</p>
            <p className="muted">
              Add a folder to scan for Markdown, text, JSON, YAML, XML, CSV/TSV, logs, config, and source files (max 10
              MiB each). Unsupported binaries stay hidden.
            </p>
          </div>
        ) : (
          <ul className="settings-folder-list">
            {lib.folders.map((f) => (
              <li key={f.id}>
                <div className="library-item-main">
                  <strong>{f.displayName}</strong>
                  <div className="library-meta muted">
                    <span className="format-chip">{f.mode === "fs-access" ? "persistent" : "fallback"}</span>
                    <span>{f.fileCount} file{f.fileCount === 1 ? "" : "s"}</span>
                    <span>Last scan: {formatScanTime(f.lastScanAt)}</span>
                    {f.inaccessible ? <span className="local-file-badge err">Inaccessible</span> : null}
                  </div>
                  {f.lastError ? <div className="muted library-desc">{f.lastError}</div> : null}
                </div>
                <button
                  type="button"
                  className="secondary"
                  disabled={lib.busyFolderId === f.id}
                  onClick={() => void lib.removeFolder(f.id)}
                >
                  Remove
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="settings-section settings-server-note">
        <h2>Server scan roots (optional)</h2>
        <p className="muted">
          The ClearRead API container cannot see your PC disks unless you mount paths into Compose. Prefer the browser
          folder picker above. If you mount a volume (for example{" "}
          <code>./notes:/data/notes:ro</code> on the <code>api</code> service), operators may add server-side scan roots
          in a future release — v1.2.x documents the constraint only and does not claim Docker can browse the whole
          machine.
        </p>
      </div>
    </section>
  );
}
