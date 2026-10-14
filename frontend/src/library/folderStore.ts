import type { PreferredFolderRecord, ScannedLocalFile } from "./folderTypes";

const DB_NAME = "clearread-local-library";
const DB_VERSION = 1;
const STORE_FOLDERS = "folders";
const STORE_HANDLES = "handles";
const STORE_FILES = "files";
const STORE_META = "meta";

const ENABLED_KEY = "clearread.localFolderLibraryEnabled";

export function isLocalFolderLibraryEnabled(): boolean {
  try {
    const v = localStorage.getItem(ENABLED_KEY);
    if (v === null) return true;
    return v === "1" || v === "true";
  } catch {
    return true;
  }
}

export function setLocalFolderLibraryEnabled(enabled: boolean): void {
  try {
    localStorage.setItem(ENABLED_KEY, enabled ? "1" : "0");
  } catch {
    /* ignore */
  }
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === "undefined") {
      reject(new Error("IndexedDB is not available in this browser."));
      return;
    }
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_FOLDERS)) {
        db.createObjectStore(STORE_FOLDERS, { keyPath: "id" });
      }
      if (!db.objectStoreNames.contains(STORE_HANDLES)) {
        db.createObjectStore(STORE_HANDLES, { keyPath: "id" });
      }
      if (!db.objectStoreNames.contains(STORE_FILES)) {
        db.createObjectStore(STORE_FILES, { keyPath: "id" });
      }
      if (!db.objectStoreNames.contains(STORE_META)) {
        db.createObjectStore(STORE_META, { keyPath: "key" });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error("Failed to open IndexedDB"));
  });
}

function idbReq<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error("IndexedDB request failed"));
  });
}

function txDone(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error("IndexedDB transaction failed"));
    tx.onabort = () => reject(tx.error ?? new Error("IndexedDB transaction aborted"));
  });
}

export async function listPreferredFolders(): Promise<PreferredFolderRecord[]> {
  const db = await openDb();
  try {
    const tx = db.transaction(STORE_FOLDERS, "readonly");
    const all = await idbReq(tx.objectStore(STORE_FOLDERS).getAll() as IDBRequest<PreferredFolderRecord[]>);
    await txDone(tx);
    return (all ?? []).sort((a, b) => a.displayName.localeCompare(b.displayName));
  } finally {
    db.close();
  }
}

export async function savePreferredFolder(
  record: PreferredFolderRecord,
  handle?: FileSystemDirectoryHandle | null
): Promise<void> {
  const db = await openDb();
  try {
    const tx = db.transaction([STORE_FOLDERS, STORE_HANDLES], "readwrite");
    tx.objectStore(STORE_FOLDERS).put(record);
    if (handle) {
      tx.objectStore(STORE_HANDLES).put({ id: record.id, handle });
    }
    await txDone(tx);
  } finally {
    db.close();
  }
}

export async function getDirectoryHandle(folderId: string): Promise<FileSystemDirectoryHandle | null> {
  const db = await openDb();
  try {
    const tx = db.transaction(STORE_HANDLES, "readonly");
    const row = await idbReq(
      tx.objectStore(STORE_HANDLES).get(folderId) as IDBRequest<{ id: string; handle: FileSystemDirectoryHandle } | undefined>
    );
    await txDone(tx);
    return row?.handle ?? null;
  } finally {
    db.close();
  }
}

export async function removePreferredFolder(folderId: string): Promise<void> {
  const db = await openDb();
  try {
    const tx = db.transaction([STORE_FOLDERS, STORE_HANDLES, STORE_FILES], "readwrite");
    tx.objectStore(STORE_FOLDERS).delete(folderId);
    tx.objectStore(STORE_HANDLES).delete(folderId);
    const files = await idbReq(tx.objectStore(STORE_FILES).getAll() as IDBRequest<ScannedLocalFile[]>);
    for (const f of files ?? []) {
      if (f.folderId === folderId) tx.objectStore(STORE_FILES).delete(f.id);
    }
    await txDone(tx);
  } finally {
    db.close();
  }
}

export async function replaceScannedFiles(folderId: string, files: ScannedLocalFile[]): Promise<void> {
  const db = await openDb();
  try {
    const tx = db.transaction(STORE_FILES, "readwrite");
    const store = tx.objectStore(STORE_FILES);
    const existing = await idbReq(store.getAll() as IDBRequest<ScannedLocalFile[]>);
    for (const f of existing ?? []) {
      if (f.folderId === folderId) store.delete(f.id);
    }
    for (const f of files) store.put(f);
    await txDone(tx);
  } finally {
    db.close();
  }
}

export async function listScannedFiles(): Promise<ScannedLocalFile[]> {
  const db = await openDb();
  try {
    const tx = db.transaction(STORE_FILES, "readonly");
    const all = await idbReq(tx.objectStore(STORE_FILES).getAll() as IDBRequest<ScannedLocalFile[]>);
    await txDone(tx);
    return (all ?? []).sort((a, b) => {
      const folderCmp = a.folderName.localeCompare(b.folderName);
      if (folderCmp !== 0) return folderCmp;
      return a.relativePath.localeCompare(b.relativePath);
    });
  } finally {
    db.close();
  }
}

export async function setLastGlobalScanAt(iso: string | null): Promise<void> {
  const db = await openDb();
  try {
    const tx = db.transaction(STORE_META, "readwrite");
    if (iso) tx.objectStore(STORE_META).put({ key: "lastScanAt", value: iso });
    else tx.objectStore(STORE_META).delete("lastScanAt");
    await txDone(tx);
  } finally {
    db.close();
  }
}

export async function getLastGlobalScanAt(): Promise<string | null> {
  const db = await openDb();
  try {
    const tx = db.transaction(STORE_META, "readonly");
    const row = await idbReq(
      tx.objectStore(STORE_META).get("lastScanAt") as IDBRequest<{ key: string; value: string } | undefined>
    );
    await txDone(tx);
    return row?.value ?? null;
  } finally {
    db.close();
  }
}

export function newFolderId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `folder-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}
