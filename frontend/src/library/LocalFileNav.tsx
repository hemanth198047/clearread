import { useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { formatApiError } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { openScannedFileInReader } from "./openLocalFile";
import type { PreferredFolderRecord, ScannedLocalFile } from "./folderTypes";

type Props = {
  folders: PreferredFolderRecord[];
  files: ScannedLocalFile[];
  enabled: boolean;
  scanning: boolean;
  lastScanAt: string | null;
  error: string | null;
  onRescan: () => void;
  /** When capabilities came from client fallback (API down). */
  formatsSource?: "api" | "fallback" | null;
  /** Compact rail title for Ingest shell. */
  title?: string;
};

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

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KiB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MiB`;
}

/** Parent directory path for grouping ("" = folder root). */
function parentDir(relativePath: string): string {
  const parts = relativePath.split("/").filter(Boolean);
  if (parts.length <= 1) return "";
  return parts.slice(0, -1).join("/");
}

export function LocalFileNav({
  folders,
  files,
  enabled,
  scanning,
  lastScanAt,
  error,
  onRescan,
  formatsSource = null,
  title = "Local files",
}: Props) {
  const auth = useAuth();
  const token = auth.getAccessToken();
  const navigate = useNavigate();
  const [openingId, setOpeningId] = useState<string | null>(null);
  const [openError, setOpenError] = useState<string | null>(null);

  const grouped = useMemo(() => {
    const map = new Map<string, { folder: PreferredFolderRecord | null; files: ScannedLocalFile[] }>();
    for (const folder of folders) {
      map.set(folder.id, { folder, files: [] });
    }
    for (const f of files) {
      const g = map.get(f.folderId) ?? { folder: null, files: [] };
      g.files.push(f);
      map.set(f.folderId, g);
    }
    return [...map.entries()];
  }, [folders, files]);

  async function onOpen(entry: ScannedLocalFile) {
    if (!token) {
      setOpenError("Sign in to open local files into ClearRead.");
      return;
    }
    setOpeningId(entry.id);
    setOpenError(null);
    try {
      const created = await openScannedFileInReader(entry, token);
      navigate(`/documents/${created.id}`);
    } catch (e) {
      const msg = formatApiError(e, "Open file");
      if (/cannot reach|too many requests|rate[_ ]?limit/i.test(msg)) {
        setOpenError(
          `${msg} The local file list is available offline; opening into the reader needs a reachable API.`
        );
      } else {
        setOpenError(msg);
      }
    } finally {
      setOpeningId(null);
    }
  }

  if (!enabled) {
    return (
      <aside className="local-file-nav" aria-label="Local files">
        <div className="local-file-nav-head">
          <h2>{title}</h2>
        </div>
        <div className="local-file-empty">
          <p>Local folder library is off.</p>
          <p className="muted">
            Enable it under <Link to="/settings">Settings</Link>.
          </p>
        </div>
      </aside>
    );
  }

  return (
    <aside className="local-file-nav" aria-label="Local files">
      <div className="local-file-nav-head">
        <h2>{title}</h2>
        <button type="button" className="secondary" onClick={onRescan} disabled={scanning}>
          {scanning ? "Scanning…" : "Re-scan"}
        </button>
      </div>
      <p className="local-file-meta muted">
        Last scan: {formatScanTime(lastScanAt)}
        {folders.length ? ` · ${folders.length} folder${folders.length === 1 ? "" : "s"}` : ""}
        {files.length ? ` · ${files.length} file${files.length === 1 ? "" : "s"}` : ""}
        {formatsSource === "fallback" ? " · offline filter" : ""}
      </p>

      {scanning && folders.length === 0 && files.length === 0 ? (
        <p className="muted local-file-none" role="status">
          Scan in progress…
        </p>
      ) : null}

      {(error || openError) && (
        <p className="alert local-file-alert" role="alert">
          {openError ?? error}
        </p>
      )}

      {folders.length === 0 ? (
        <div className="local-file-empty">
          <p>No preferred folders configured.</p>
          <p className="muted">
            Add folders in Settings. ClearRead lists matching files recursively — nothing is uploaded until you open
            one.
          </p>
          <p>
            <Link to="/settings">Open Settings</Link>
          </p>
        </div>
      ) : (
        <nav className="local-file-tree" aria-label="Scanned files">
          {grouped.map(([folderId, group]) => {
            const folder = group.folder;
            const label = folder?.displayName ?? group.files[0]?.folderName ?? "Folder";
            const byPath = new Map<string, ScannedLocalFile[]>();
            for (const f of group.files) {
              const dir = parentDir(f.relativePath);
              const list = byPath.get(dir) ?? [];
              list.push(f);
              byPath.set(dir, list);
            }
            const pathEntries = [...byPath.entries()].sort(([a], [b]) => a.localeCompare(b));

            return (
              <div key={folderId} className="local-file-group">
                <div className="local-file-group-label">
                  <span className="local-file-folder-name" title={label}>
                    {label}
                  </span>
                  {folder?.inaccessible ? (
                    <span className="local-file-badge err">Inaccessible</span>
                  ) : (
                    <span className="local-file-badge">{group.files.length}</span>
                  )}
                </div>
                {folder?.lastError ? <p className="local-file-folder-error muted">{folder.lastError}</p> : null}
                {scanning && group.files.length === 0 && !folder?.inaccessible ? (
                  <p className="muted local-file-none">Scanning…</p>
                ) : group.files.length === 0 && !folder?.inaccessible ? (
                  <p className="muted local-file-none">No readable files found</p>
                ) : (
                  pathEntries.map(([dir, dirFiles]) => (
                    <div key={`${folderId}:${dir || "."}`} className="local-file-subdir">
                      {dir ? (
                        <p className="local-file-subdir-label muted" title={dir}>
                          {dir}/
                        </p>
                      ) : null}
                      <ul>
                        {dirFiles.map((f) => (
                          <li key={f.id}>
                            <button
                              type="button"
                              className="local-file-item"
                              disabled={openingId === f.id}
                              title={`${f.relativePath} (${formatBytes(f.size)})`}
                              onClick={() => void onOpen(f)}
                            >
                              <span className="local-file-item-name">{f.name}</span>
                              <span className="local-file-item-meta muted">
                                {f.formatId ?? f.extension ?? "file"}
                                {openingId === f.id ? " · opening…" : ""}
                              </span>
                            </button>
                          </li>
                        ))}
                      </ul>
                    </div>
                  ))
                )}
              </div>
            );
          })}
        </nav>
      )}
    </aside>
  );
}
