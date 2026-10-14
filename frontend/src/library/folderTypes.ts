export type FolderPersistMode = "fs-access" | "webkit-directory";

export type PreferredFolderRecord = {
  id: string;
  displayName: string;
  mode: FolderPersistMode;
  addedAt: string;
  lastScanAt: string | null;
  lastError: string | null;
  fileCount: number;
  /** True when permission was denied or handle is no longer usable. */
  inaccessible: boolean;
};

export type ScannedLocalFile = {
  id: string;
  folderId: string;
  folderName: string;
  /** Path relative to the preferred folder root, using `/`. */
  relativePath: string;
  name: string;
  size: number;
  type: string;
  formatId?: string;
  extension?: string | null;
};

export type FolderScanSummary = {
  folderId: string;
  scannedAt: string;
  matched: number;
  skippedUnsupported: number;
  skippedOversized: number;
  skippedOther: number;
  error: string | null;
};

export type LocalLibraryState = {
  folders: PreferredFolderRecord[];
  files: ScannedLocalFile[];
  lastScanAt: string | null;
  scanning: boolean;
  enabled: boolean;
};
