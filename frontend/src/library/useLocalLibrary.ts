import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "../api/client";
import {
  formatsForScan,
  type CapabilityFormat,
  type Capabilities,
} from "./capabilities";
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

/** Module-level capabilities cache so Home/Library/Reader/Settings do not stampede /v1/capabilities. */
let capabilitiesCache: {
  formats: CapabilityFormat[];
  data: Capabilities | null;
  at: number;
  source: "api" | "fallback";
} | null = null;
let capabilitiesInflight: Promise<{
  formats: CapabilityFormat[];
  data: Capabilities | null;
  source: "api" | "fallback";
}> | null = null;
const CAPABILITIES_TTL_MS = 60_000;

export function clearCapabilitiesCacheForTests(): void {
  capabilitiesCache = null;
  capabilitiesInflight = null;
}

/**
 * Shared GET /v1/capabilities with TTL + in-flight dedupe.
 * Home, Library, and Reader must use this instead of raw apiFetch to avoid duplicate network calls.
 */
export async function fetchCapabilitiesCached(): Promise<{
  formats: CapabilityFormat[];
  data: Capabilities | null;
  source: "api" | "fallback";
}> {
  const now = Date.now();
  if (capabilitiesCache && now - capabilitiesCache.at < CAPABILITIES_TTL_MS) {
    return {
      formats: capabilitiesCache.formats,
      data: capabilitiesCache.data,
      source: capabilitiesCache.source,
    };
  }
  if (capabilitiesInflight) return capabilitiesInflight;

  capabilitiesInflight = (async () => {
    try {
      const data = (await apiFetch("/v1/capabilities", null)) as Capabilities;
      const list = data.formats ?? [];
      if (list.length > 0) {
        capabilitiesCache = { formats: list, data, at: Date.now(), source: "api" };
        return { formats: list, data, source: "api" as const };
      }
      const fb = formatsForScan(null);
      capabilitiesCache = { formats: fb.formats, data: null, at: Date.now(), source: "fallback" };
      return { formats: fb.formats, data: null, source: "fallback" as const };
    } catch {
      const fb = formatsForScan(null);
      // Shorter TTL on failure so we retry sooner when API recovers.
      capabilitiesCache = {
        formats: fb.formats,
        data: null,
        at: Date.now() - CAPABILITIES_TTL_MS + 15_000,
        source: "fallback",
      };
      return { formats: fb.formats, data: null, source: "fallback" as const };
    } finally {
      capabilitiesInflight = null;
    }
  })();

  return capabilitiesInflight;
}

async function loadFormatsCached(): Promise<{ formats: CapabilityFormat[]; source: "api" | "fallback" }> {
  const result = await fetchCapabilitiesCached();
  return { formats: result.formats, source: result.source };
}

export function useLocalLibrary(autoScan: boolean) {
  const [enabled, setEnabled] = useState(() => isLocalFolderLibraryEnabled());
  const [folders, setFolders] = useState<PreferredFolderRecord[]>([]);
  const [files, setFiles] = useState<ScannedLocalFile[]>([]);
  const [formats, setFormats] = useState<CapabilityFormat[] | null>(null);
  const [formatsSource, setFormatsSource] = useState<"api" | "fallback" | null>(null);
  const [lastScanAt, setLastScanAt] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyFolderId, setBusyFolderId] = useState<string | null>(null);
  const formatsRef = useRef<CapabilityFormat[] | null>(null);
  formatsRef.current = formats;
  const autoScanStarted = useRef(false);

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
    if (formatsReadyForScan(formatsRef.current) && (formatsRef.current?.length ?? 0) > 0) {
      return formatsRef.current ?? [];
    }
    const loaded = await loadFormatsCached();
    setFormats(loaded.formats);
    setFormatsSource(loaded.source);
    formatsRef.current = loaded.formats;
    return loaded.formats;
  }, []);

  useEffect(() => {
    void ensureFormats().catch(() => {
      const fb = formatsForScan(null);
      setFormats(fb.formats);
      setFormatsSource(fb.source);
      formatsRef.current = fb.formats;
    });
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
      else if (formatsSource === "fallback" || (fmt.length > 0 && capabilitiesCache?.source === "fallback")) {
        setError(null);
      }
      notifyLocalLibraryChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setScanning(false);
    }
  }, [enabled, ensureFormats, formatsSource]);

  useEffect(() => {
    if (!autoScan || !enabled || !formatsReadyForScan(formats)) return;
    if (autoScanStarted.current) return;
    autoScanStarted.current = true;
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
      if (scanned.summary.error && scanned.files.length === 0 && scanned.folder.inaccessible) {
        setError(scanned.summary.error);
      } else if (scanned.summary.error && scanned.files.length === 0) {
        setError(scanned.summary.error);
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
    formatsSource,
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

export type LocalLibraryApi = ReturnType<typeof useLocalLibrary>;
