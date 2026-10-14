import { apiFetch } from "../api/client";
import { getDirectoryHandle } from "./folderStore";
import { resolveFileHandle } from "./fsAccess";
import { getCachedWebkitFile } from "./folderScan";
import type { ScannedLocalFile } from "./folderTypes";

export type IngestedDocMeta = {
  id: string;
  title: string;
  status: string;
  formatId: string;
  jobId?: string;
  duplicateOfExisting?: boolean;
  restoredStorage?: boolean;
  message?: string;
};

export async function waitUntilReady(
  id: string,
  accessToken: string,
  onProgress?: (status: string) => void
): Promise<IngestedDocMeta> {
  for (let i = 0; i < 80; i++) {
    const meta = (await apiFetch(`/v1/documents/${id}`, accessToken)) as IngestedDocMeta;
    onProgress?.(meta.status);
    if (meta.status === "ready") return meta;
    if (meta.status === "failed" || meta.status === "cancelled") {
      throw new Error(`Processing ${meta.status}`);
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error("Timed out waiting for document");
}

/** Load bytes for a scanned local file (FS Access handle or webkit session cache). */
export async function readScannedLocalFile(entry: ScannedLocalFile): Promise<File> {
  const cached = getCachedWebkitFile(entry.id);
  if (cached) return cached;

  const root = await getDirectoryHandle(entry.folderId);
  if (!root) {
    throw new Error(
      "Cannot open this file — folder access is not available. Re-add the folder in Settings (Chrome/Edge recommended)."
    );
  }
  const fileHandle = await resolveFileHandle(root, entry.relativePath);
  return fileHandle.getFile();
}

/**
 * Upload a local library file through the existing ingest pipeline (soft-dedup preserved).
 */
export async function openScannedFileInReader(
  entry: ScannedLocalFile,
  accessToken: string,
  options?: { forceNew?: boolean; onProgress?: (msg: string) => void }
): Promise<IngestedDocMeta> {
  options?.onProgress?.("Reading local file…");
  const file = await readScannedLocalFile(entry);

  options?.onProgress?.("Uploading…");
  const form = new FormData();
  form.append("file", file, entry.name);
  form.append("title", entry.name);
  if (options?.forceNew) form.append("forceNew", "true");

  const created = (await apiFetch("/v1/documents/upload", accessToken, {
    method: "POST",
    body: form,
  })) as IngestedDocMeta;

  if (created.restoredStorage) {
    options?.onProgress?.(created.message ?? "Restored missing storage — reprocessing…");
  } else if (created.duplicateOfExisting) {
    options?.onProgress?.(created.message ?? "Opening existing document…");
  }
  if (created.status !== "ready") {
    return waitUntilReady(created.id, accessToken, (s) => options?.onProgress?.(`Status: ${s}`));
  }
  return created;
}
