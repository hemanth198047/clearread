import type { ReaderThemeId } from "./themes";

export type SegmentationMode = "conservative" | "balanced" | "detailed";
export type ReaderViewMode = "read" | "original" | "compare";
export type FontFamilyId = "source-sans" | "literata" | "ibm-plex-sans" | "ibm-plex-mono";

export type ReaderPreferences = {
  theme: ReaderThemeId;
  fontFamily: FontFamilyId;
  fontSizePx: number;
  lineHeight: number;
  measureCh: number;
  paragraphSpacingEm: number;
  headingSpacingEm: number;
  codeFontSizePx: number;
  showLineNumbers: boolean;
  sectionCollapsing: boolean;
  segmentation: SegmentationMode;
  tocOpen: boolean;
  viewMode: ReaderViewMode;
};

export const DEFAULT_PREFERENCES: ReaderPreferences = {
  theme: "day",
  fontFamily: "literata",
  fontSizePx: 18,
  lineHeight: 1.7,
  measureCh: 68,
  paragraphSpacingEm: 0.85,
  headingSpacingEm: 1.35,
  codeFontSizePx: 14,
  showLineNumbers: false,
  sectionCollapsing: true,
  segmentation: "conservative",
  tocOpen: true,
  viewMode: "read",
};

export const LOCAL_PREFS_KEY = "clearread.reader.preferences.v1";
export const SCROLL_KEY_PREFIX = "clearread.reader.scroll.";

/**
 * Normalize Structure-control values from localStorage / server / mis-cased UI.
 * Wrong keys (e.g. "Detailed") must still resolve so Detailed never blanks the reader.
 */
export function normalizeSegmentationMode(mode: unknown): SegmentationMode {
  const raw = String(mode ?? "")
    .trim()
    .toLowerCase()
    .replace(/[\s_-]+/g, "");
  if (raw === "detailed" || raw === "detail" || raw === "full" || raw === "all") return "detailed";
  if (raw === "balanced" || raw === "balance") return "balanced";
  if (raw === "conservative" || raw === "minimal" || raw === "compact") return "conservative";
  return DEFAULT_PREFERENCES.segmentation;
}

export function fontFamilyCss(id: FontFamilyId): string {
  switch (id) {
    case "literata":
      return '"Literata", "Fraunces", Georgia, serif';
    case "ibm-plex-sans":
      return '"IBM Plex Sans", "Source Sans 3", sans-serif';
    case "ibm-plex-mono":
      return '"IBM Plex Mono", ui-monospace, monospace';
    default:
      return '"Source Sans 3", "Segoe UI", sans-serif';
  }
}

export function mergePreferences(partial: Partial<ReaderPreferences> | null | undefined): ReaderPreferences {
  const merged = { ...DEFAULT_PREFERENCES, ...(partial ?? {}) };
  merged.segmentation = normalizeSegmentationMode(merged.segmentation);
  return merged;
}

export function loadLocalPreferences(): ReaderPreferences {
  try {
    const raw = localStorage.getItem(LOCAL_PREFS_KEY);
    if (!raw) return DEFAULT_PREFERENCES;
    return mergePreferences(JSON.parse(raw) as Partial<ReaderPreferences>);
  } catch {
    return DEFAULT_PREFERENCES;
  }
}

export function saveLocalPreferences(prefs: ReaderPreferences) {
  localStorage.setItem(LOCAL_PREFS_KEY, JSON.stringify(prefs));
}
