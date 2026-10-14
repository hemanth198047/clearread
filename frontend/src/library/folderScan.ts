import {
  DEFAULT_MAX_UPLOAD_BYTES,
  buildSupportedIndex,
  formatsForScan,
  type CapabilityFormat,
} from "./capabilities";
import { filterLocalFile, shouldSkipDirectoryName } from "./fileFilter";
import {
  DEFAULT_MAX_SCAN_DEPTH,
  DEFAULT_MAX_SCAN_FILES,
  filterFlatRelativeHits,
  type WalkHit,
  type WalkLimits,
} from "./folderWalk";
import {
  getDirectoryHandle,
  listPreferredFolders,
  listScannedFiles,
  newFolderId,
  replaceScannedFiles,
  savePreferredFolder,
  setLastGlobalScanAt,
} from "./folderStore";
import { ensureReadPermission } from "./fsAccess";
import type { FolderScanSummary, PreferredFolderRecord, ScannedLocalFile } from "./folderTypes";

export type ScanOptions = {
  formats: CapabilityFormat[] | null | undefined;
  maxBytes?: number;
  maxDepth?: number;
  maxFiles?: number;
};

export type { WalkHit };

/**
 * Recursively walk a File System Access directory handle to leaf folders,
 * respecting ignore names, max depth, and max file count.
 */
export async function walkDirectoryHandle(
  dir: FileSystemDirectoryHandle,
  prefix: string,
  out: WalkHit[],
  limits: WalkLimits = {},
  depth = 0
): Promise<void> {
  const maxFiles = limits.maxFiles ?? DEFAULT_MAX_SCAN_FILES;
  const maxDepth = limits.maxDepth ?? DEFAULT_MAX_SCAN_DEPTH;
  if (out.length >= maxFiles) return;

  for await (const [name, handle] of dir.entries()) {
    if (out.length >= maxFiles) return;
    if (handle.kind === "directory") {
      if (shouldSkipDirectoryName(name)) continue;
      if (depth >= maxDepth) continue;
      const next = prefix ? `${prefix}/${name}` : name;
      await walkDirectoryHandle(handle as FileSystemDirectoryHandle, next, out, limits, depth + 1);
    } else if (handle.kind === "file") {
      try {
        const file = await (handle as FileSystemFileHandle).getFile();
        const relativePath = prefix ? `${prefix}/${name}` : name;
        out.push({
          relativePath,
          name: file.name,
          size: file.size,
          type: file.type || "",
        });
      } catch {
        /* unreadable file — skip */
      }
    }
  }
}

function toScannedFiles(
  folder: PreferredFolderRecord,
  hits: WalkHit[],
  formats: CapabilityFormat[] | null | undefined,
  maxBytes: number
): { files: ScannedLocalFile[]; summary: Omit<FolderScanSummary, "folderId" | "scannedAt" | "error"> } {
  const { formats: effective } = formatsForScan(formats);
  const index = buildSupportedIndex(effective);
  const files: ScannedLocalFile[] = [];
  let skippedUnsupported = 0;
  let skippedOversized = 0;
  let skippedOther = 0;

  for (const hit of hits) {
    const result = filterLocalFile(
      { name: hit.name, size: hit.size, type: hit.type, relativePath: hit.relativePath },
      { index, maxBytes }
    );
    if (!result.ok) {
      if (result.reason === "unsupported_extension") skippedUnsupported++;
      else if (result.reason === "oversized") skippedOversized++;
      else skippedOther++;
      continue;
    }
    files.push({
      id: `${folder.id}:${hit.relativePath}`,
      folderId: folder.id,
      folderName: folder.displayName,
      relativePath: hit.relativePath,
      name: hit.name,
      size: hit.size,
      type: hit.type,
      formatId: result.formatId,
      extension: result.extension,
    });
  }

  return {
    files,
    summary: {
      matched: files.length,
      skippedUnsupported,
      skippedOversized,
      skippedOther,
    },
  };
}

function emptyNoReadableError(matched: number, skippedUnsupported: number): string | null {
  if (matched > 0) return null;
  if (skippedUnsupported > 0) {
    return "No readable files found — scanned files did not match ClearRead-supported extensions (or exceeded 10 MiB).";
  }
  return "No readable files found in this folder (including subfolders).";
}

export async function scanPreferredFolder(
  folder: PreferredFolderRecord,
  options: ScanOptions
): Promise<{ files: ScannedLocalFile[]; summary: FolderScanSummary; folder: PreferredFolderRecord }> {
  const maxBytes = options.maxBytes ?? DEFAULT_MAX_UPLOAD_BYTES;
  const limits: WalkLimits = {
    maxDepth: options.maxDepth ?? DEFAULT_MAX_SCAN_DEPTH,
    maxFiles: options.maxFiles ?? DEFAULT_MAX_SCAN_FILES,
  };
  const scannedAt = new Date().toISOString();

  if (folder.mode !== "fs-access") {
    const summary: FolderScanSummary = {
      folderId: folder.id,
      scannedAt,
      matched: folder.fileCount,
      skippedUnsupported: 0,
      skippedOversized: 0,
      skippedOther: 0,
      error:
        "This folder was added via the browser directory picker fallback. Re-add the folder to refresh the file list (Safari/Firefox cannot persist directory access).",
    };
    const updated: PreferredFolderRecord = {
      ...folder,
      lastScanAt: scannedAt,
      lastError: summary.error,
      inaccessible: false,
    };
    await savePreferredFolder(updated);
    return { files: [], summary, folder: updated };
  }

  const handle = await getDirectoryHandle(folder.id);
  if (!handle) {
    const error = "Saved folder handle is missing. Remove and re-add this folder.";
    const updated: PreferredFolderRecord = {
      ...folder,
      lastScanAt: scannedAt,
      lastError: error,
      inaccessible: true,
      fileCount: 0,
    };
    await savePreferredFolder(updated);
    await replaceScannedFiles(folder.id, []);
    return {
      files: [],
      summary: {
        folderId: folder.id,
        scannedAt,
        matched: 0,
        skippedUnsupported: 0,
        skippedOversized: 0,
        skippedOther: 0,
        error,
      },
      folder: updated,
    };
  }

  const permitted = await ensureReadPermission(handle);
  if (!permitted) {
    const error = "Permission denied — grant folder access when prompted, or re-add the folder in Settings.";
    const updated: PreferredFolderRecord = {
      ...folder,
      lastScanAt: scannedAt,
      lastError: error,
      inaccessible: true,
      fileCount: 0,
    };
    await savePreferredFolder(updated);
    await replaceScannedFiles(folder.id, []);
    return {
      files: [],
      summary: {
        folderId: folder.id,
        scannedAt,
        matched: 0,
        skippedUnsupported: 0,
        skippedOversized: 0,
        skippedOther: 0,
        error,
      },
      folder: updated,
    };
  }

  try {
    const hits: WalkHit[] = [];
    await walkDirectoryHandle(handle, "", hits, limits, 0);
    const { files, summary: counts } = toScannedFiles(folder, hits, options.formats, maxBytes);
    const softError = emptyNoReadableError(counts.matched, counts.skippedUnsupported);
    const updated: PreferredFolderRecord = {
      ...folder,
      displayName: handle.name || folder.displayName,
      lastScanAt: scannedAt,
      lastError: softError,
      inaccessible: false,
      fileCount: files.length,
    };
    await savePreferredFolder(updated, handle);
    await replaceScannedFiles(folder.id, files);
    return {
      files,
      summary: { folderId: folder.id, scannedAt, error: softError, ...counts },
      folder: updated,
    };
  } catch (e) {
    const error = e instanceof Error ? e.message : String(e);
    const updated: PreferredFolderRecord = {
      ...folder,
      lastScanAt: scannedAt,
      lastError: error,
      inaccessible: true,
      fileCount: 0,
    };
    await savePreferredFolder(updated);
    await replaceScannedFiles(folder.id, []);
    return {
      files: [],
      summary: {
        folderId: folder.id,
        scannedAt,
        matched: 0,
        skippedUnsupported: 0,
        skippedOversized: 0,
        skippedOther: 0,
        error,
      },
      folder: updated,
    };
  }
}

export async function scanAllPreferredFolders(options: ScanOptions): Promise<{
  folders: PreferredFolderRecord[];
  files: ScannedLocalFile[];
  summaries: FolderScanSummary[];
  lastScanAt: string;
}> {
  const folders = await listPreferredFolders();
  const summaries: FolderScanSummary[] = [];
  const allFiles: ScannedLocalFile[] = [];
  const updatedFolders: PreferredFolderRecord[] = [];

  for (const folder of folders) {
    if (folder.mode === "webkit-directory") {
      // Keep previously stored file list; surface re-add hint without wiping.
      const scannedAt = new Date().toISOString();
      const existing = (await listScannedFiles()).filter((f) => f.folderId === folder.id);
      const error =
        "Fallback folder: list is from the last picker session. Re-add the folder to refresh (no persistent directory handles in this browser).";
      const updated: PreferredFolderRecord = {
        ...folder,
        lastScanAt: scannedAt,
        lastError: existing.length ? error : "Re-add this folder to scan files.",
        fileCount: existing.length,
        inaccessible: existing.length === 0,
      };
      await savePreferredFolder(updated);
      updatedFolders.push(updated);
      allFiles.push(...existing);
      summaries.push({
        folderId: folder.id,
        scannedAt,
        matched: existing.length,
        skippedUnsupported: 0,
        skippedOversized: 0,
        skippedOther: 0,
        error: updated.lastError,
      });
      continue;
    }
    const result = await scanPreferredFolder(folder, options);
    updatedFolders.push(result.folder);
    allFiles.push(...result.files);
    summaries.push(result.summary);
  }

  const lastScanAt = new Date().toISOString();
  await setLastGlobalScanAt(lastScanAt);
  return { folders: updatedFolders, files: allFiles, summaries, lastScanAt };
}

/**
 * Ingest a FileList from `<input webkitdirectory>` into the local library store.
 * The picker already returns nested files; we still apply ignore / depth / max-file rules.
 */
export async function ingestWebkitDirectoryFiles(
  displayName: string,
  fileList: FileList | File[],
  options: ScanOptions
): Promise<{ folder: PreferredFolderRecord; files: ScannedLocalFile[] }> {
  const maxBytes = options.maxBytes ?? DEFAULT_MAX_UPLOAD_BYTES;
  const limits: WalkLimits = {
    maxDepth: options.maxDepth ?? DEFAULT_MAX_SCAN_DEPTH,
    maxFiles: options.maxFiles ?? DEFAULT_MAX_SCAN_FILES,
  };
  const id = newFolderId();
  const scannedAt = new Date().toISOString();
  const filesArr = Array.from(fileList as ArrayLike<File>);

  // Infer folder display name from first webkitRelativePath segment when possible.
  let name = displayName.trim() || "Selected folder";
  const firstRel =
    filesArr[0] && "webkitRelativePath" in filesArr[0]
      ? (filesArr[0] as File & { webkitRelativePath?: string }).webkitRelativePath
      : "";
  if (firstRel) {
    const root = firstRel.split("/")[0];
    if (root) name = root;
  }

  const folder: PreferredFolderRecord = {
    id,
    displayName: name,
    mode: "webkit-directory",
    addedAt: scannedAt,
    lastScanAt: scannedAt,
    lastError: null,
    fileCount: 0,
    inaccessible: false,
  };

  const hits: WalkHit[] = filesArr.map((f) => {
    const rel =
      "webkitRelativePath" in f && (f as File & { webkitRelativePath?: string }).webkitRelativePath
        ? (f as File & { webkitRelativePath: string }).webkitRelativePath
        : f.name;
    // Strip root folder prefix for relative path consistency
    const parts = rel.split("/");
    const relativePath = parts.length > 1 ? parts.slice(1).join("/") : parts[0]!;
    return {
      relativePath: relativePath || f.name,
      name: f.name,
      size: f.size,
      type: f.type || "",
    };
  });

  const filteredHits = filterFlatRelativeHits(hits, limits);

  const { files, summary: counts } = toScannedFiles(folder, filteredHits, options.formats, maxBytes);
  folder.fileCount = files.length;
  folder.lastError = emptyNoReadableError(counts.matched, counts.skippedUnsupported);

  await savePreferredFolder(folder);
  await replaceScannedFiles(folder.id, files);
  await setLastGlobalScanAt(scannedAt);
  cacheWebkitFiles(folder.id, filesArr);
  return { folder, files };
}

/** Session-only File blobs for webkit fallback open (cleared on reload). */
const webkitFileCache = new Map<string, File>();

export function cacheWebkitFiles(folderId: string, fileList: FileList | File[]): void {
  for (const f of Array.from(fileList as ArrayLike<File>)) {
    const rel =
      "webkitRelativePath" in f && (f as File & { webkitRelativePath?: string }).webkitRelativePath
        ? (f as File & { webkitRelativePath: string }).webkitRelativePath
        : f.name;
    const parts = rel.split("/");
    const relativePath = parts.length > 1 ? parts.slice(1).join("/") : parts[0]!;
    webkitFileCache.set(`${folderId}:${relativePath || f.name}`, f);
  }
}

export function getCachedWebkitFile(fileId: string): File | undefined {
  return webkitFileCache.get(fileId);
}
