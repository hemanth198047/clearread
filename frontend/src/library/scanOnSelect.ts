import type { CapabilityFormat } from "./capabilities";
import type { FolderScanSummary, PreferredFolderRecord, ScannedLocalFile } from "./folderTypes";

/**
 * True when capability formats have finished loading (success or empty/failure).
 * `null` means still in flight — do not scan yet or the extension index is empty
 * and every file looks "unsupported".
 */
export function formatsReadyForScan(formats: CapabilityFormat[] | null): boolean {
  return formats !== null;
}

export type ImmediateScanResult = {
  folders: PreferredFolderRecord[];
  files: ScannedLocalFile[];
  lastScanAt: string;
  error: string | null;
};

/**
 * Pick a user-facing error after an immediate post-select scan.
 * Prefer permission / inaccessible messages over soft fallback hints.
 */
export function errorFromScanSummaries(summaries: FolderScanSummary[]): string | null {
  for (const s of summaries) {
    if (!s.error) continue;
    const lower = s.error.toLowerCase();
    if (
      lower.includes("permission") ||
      lower.includes("denied") ||
      lower.includes("missing") ||
      lower.includes("inaccessible") ||
      lower.includes("failed")
    ) {
      return s.error;
    }
  }
  for (const s of summaries) {
    if (s.error && s.matched === 0) return s.error;
  }
  return null;
}

/**
 * Decide whether Add-folder should kick off an immediate scan (vs waiting for
 * next Library mount / app start). Always true once the picker succeeded.
 */
export function shouldScanImmediatelyAfterFolderSelect(): boolean {
  return true;
}
