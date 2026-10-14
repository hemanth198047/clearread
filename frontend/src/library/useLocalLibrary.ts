import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "../api/client";
import type { CapabilityFormat, Capabilities } from "./capabilities";
import {
  ingestWebkitDirectoryFiles,
  scanAllPreferredFolders,
  scanPreferredFolder,
} from "./folderScan";
import {
  getLastGlobalScanAt,
  isLocalFolderLibraryEnabled,
  listPreferredFolders,
  listScannedFiles,
  newFolderId,
  removePreferredFolder,
  savePreferredFolder,
  setLastGlobalScanAt,
  setLocalFolderLibraryEnabled,
} from "./folderStore";
import { pickDirectory, supportsDirectoryPicker, supportsWebkitDirectory } from "./fsAccess";
import type { PreferredFolderRecord, ScannedLocalFile } from "./folderTypes";
import { errorFromScanSummaries, formatsReadyForScan } from "./scanOnSelect";

/** Broadcast so other mounted library views can refresh after Settings add/scan. */
export const LOCAL_LIBRARY_CHANGED_EVENT = "clearread:local-library-changed";

function notifyLocalLibraryChanged(): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new Event(LOCAL_LIBRARY_CHANGED_EVENT));
}

export function useLocalLibrary(autoScan: boolean) {
  const [enabled, setEnabled] = useState(() => isLocalFolderLibraryEnabled());
  const [folders, setFolders] = useState<PreferredFolderRecord[]>([]);
  const [files, setFiles] = useState<ScannedLocalFile[]>([]);
  const [formats, setFormats] = useState<CapabilityFormat[] | null>(null);
  const [lastScanAt, setLastScanAt] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyFolderId, setBusyFolderId] = useState<string | null>(null);
  const formatsRef = useRef<CapabilityFormat[] | null>(null);
  formatsRef.current = formats;

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

  const ensureFormats = useCallback(async (): Promise<CapabilityFormat[]> => {
    if (formatsReadyForScan(formatsRef.current)) {
      return formatsRef.current ?? [];
    }
    try {
      const data = (await apiFetch("/v1/capabilities", null)) as Capabilities;
      const list = data.formats ?? [];
      setFormats(list);
      formatsRef.current = list;
      return list;
    } catch {
      const empty: CapabilityFormat[] = [];
      setFormats(empty);
      formatsRef.current = empty;
      return empty;
    }
  }, []);

  useEffect(() => {
    void ensureFormats().catch(() => setFormats([]));
  }, [ensureFormats]);

  useEffect(() => {
    void reloadLists().catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [reloadLists]);

  useEffect(() => {
    const onChanged = () => {
      void reloadLists().catch((e) => setError(e instanceof Error ? e.message : String(e)));
    };
    window.addEventListener(LOCAL_LIBRARY_CHANGED_EVENT, onChanged);
    return () => window.removeEventListener(LOCAL_LIBRARY_CHANGED_EVENT, onChanged);
  }, [reloadLists]);

  const rescan = useCallback(async () => {
    if (!enabled) return;
    setScanning(true);
    setError(null);
    try {
      const fmt = await ensureFormats();
      const result = await scanAllPreferredFolders({ formats: fmt });
      setFolders(result.folders);
      setFiles(result.files);
      setLastScanAt(result.lastScanAt);
      const scanErr = errorFromScanSummaries(result.summaries);
      if (scanErr) setError(scanErr);
      notifyLocalLibraryChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setScanning(false);
    }
  }, [enabled, ensureFormats]);

  useEffect(() => {
    if (!autoScan || !enabled || !formatsReadyForScan(formats)) return;
    void rescan();
  }, [autoScan, enabled, formats, rescan]);

  async function setEnabledPref(next: boolean) {
    setLocalFolderLibraryEnabled(next);
    setEnabled(next);
  }

  /**
   * After the directory picker succeeds, scan that folder immediately
   * (do not wait for next app start / Library mount).
   */
  async function addFolderFsAccess() {
    setError(null);
    setBusyFolderId("new");
    try {
      const handle = await pickDirectory();
      setScanning(true);
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
      const fmt = await ensureFormats();
      const scanned = await scanPreferredFolder(record, { formats: fmt });
      await setLastGlobalScanAt(scanned.summary.scannedAt);
      await reloadLists();
      if (scanned.summary.error) {
        setError(scanned.summary.error);
      } else if (fmt.length === 0) {
        setError("Could not load format capabilities; file list may be incomplete. Try Re-scan after the API is up.");
      }
      notifyLocalLibraryChanged();
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") return;
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyFolderId(null);
      setScanning(false);
    }
  }

  async function addFolderWebkit(fileList: FileList) {
    if (!fileList.length) return;
    setError(null);
    setBusyFolderId("new");
    setScanning(true);
    try {
      const fmt = await ensureFormats();
      const { folder } = await ingestWebkitDirectoryFiles("Selected folder", fileList, { formats: fmt });
      await reloadLists();
      if (folder.lastError) setError(folder.lastError);
      else if (fmt.length === 0) {
        setError("Could not load format capabilities; file list may be incomplete. Try Re-scan after the API is up.");
      }
      notifyLocalLibraryChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyFolderId(null);
      setScanning(false);
    }
  }

  async function removeFolder(folderId: string) {
    setBusyFolderId(folderId);
    setError(null);
    try {
      await removePreferredFolder(folderId);
      await reloadLists();
      notifyLocalLibraryChanged();
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
