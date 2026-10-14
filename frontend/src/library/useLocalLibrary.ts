import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "../api/client";
import type { CapabilityFormat, Capabilities } from "./capabilities";
import {
  cacheWebkitFiles,
  ingestWebkitDirectoryFiles,
  scanAllPreferredFolders,
} from "./folderScan";
import {
  getLastGlobalScanAt,
  isLocalFolderLibraryEnabled,
  listPreferredFolders,
  listScannedFiles,
  newFolderId,
  removePreferredFolder,
  savePreferredFolder,
  setLocalFolderLibraryEnabled,
} from "./folderStore";
import { pickDirectory, supportsDirectoryPicker, supportsWebkitDirectory } from "./fsAccess";
import type { PreferredFolderRecord, ScannedLocalFile } from "./folderTypes";

export function useLocalLibrary(autoScan: boolean) {
  const [enabled, setEnabled] = useState(() => isLocalFolderLibraryEnabled());
  const [folders, setFolders] = useState<PreferredFolderRecord[]>([]);
  const [files, setFiles] = useState<ScannedLocalFile[]>([]);
  const [formats, setFormats] = useState<CapabilityFormat[] | null>(null);
  const [lastScanAt, setLastScanAt] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyFolderId, setBusyFolderId] = useState<string | null>(null);

  const fsAccess = supportsDirectoryPicker();
  const webkitDir = supportsWebkitDirectory();

  const reloadLists = useCallback(async () => {
    const [f, fl, scanAt] = await Promise.all([
      listPreferredFolders(),
      listScannedFiles(),
      getLastGlobalScanAt(),
    ]);
    setFolders(f);
    setFiles(fl);
    setLastScanAt(scanAt);
  }, []);

  useEffect(() => {
    apiFetch("/v1/capabilities", null)
      .then((data) => {
        const caps = data as Capabilities;
        setFormats(caps.formats ?? []);
      })
      .catch(() => setFormats([]));
  }, []);

  useEffect(() => {
    void reloadLists().catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [reloadLists]);

  const rescan = useCallback(async () => {
    if (!enabled) return;
    setScanning(true);
    setError(null);
    try {
      const result = await scanAllPreferredFolders({ formats });
      setFolders(result.folders);
      setFiles(result.files);
      setLastScanAt(result.lastScanAt);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setScanning(false);
    }
  }, [enabled, formats]);

  useEffect(() => {
    if (!autoScan || !enabled || formats === null) return;
    void rescan();
  }, [autoScan, enabled, formats, rescan]);

  async function setEnabledPref(next: boolean) {
    setLocalFolderLibraryEnabled(next);
    setEnabled(next);
  }

  async function addFolderFsAccess() {
    setError(null);
    setBusyFolderId("new");
    try {
      const handle = await pickDirectory();
      const id = newFolderId();
      const now = new Date().toISOString();
      const record: PreferredFolderRecord = {
        id,
        displayName: handle.name || "Folder",
        mode: "fs-access",
        addedAt: now,
        lastScanAt: null,
        lastError: null,
        fileCount: 0,
        inaccessible: false,
      };
      await savePreferredFolder(record, handle);
      await reloadLists();
      await rescan();
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") return;
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyFolderId(null);
    }
  }

  async function addFolderWebkit(fileList: FileList) {
    if (!fileList.length) return;
    setError(null);
    setBusyFolderId("new");
    try {
      const { folder } = await ingestWebkitDirectoryFiles("Selected folder", fileList, { formats });
      cacheWebkitFiles(folder.id, fileList);
      await reloadLists();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyFolderId(null);
    }
  }

  async function removeFolder(folderId: string) {
    setBusyFolderId(folderId);
    setError(null);
    try {
      await removePreferredFolder(folderId);
      await reloadLists();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyFolderId(null);
    }
  }

  return {
    enabled,
    setEnabledPref,
    folders,
    files,
    formats,
    lastScanAt,
    scanning,
    error,
    setError,
    busyFolderId,
    fsAccess,
    webkitDir,
    rescan,
    addFolderFsAccess,
    addFolderWebkit,
    removeFolder,
    reloadLists,
  };
}
