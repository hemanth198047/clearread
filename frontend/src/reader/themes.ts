export type ReaderThemeId = "day" | "sepia" | "night" | "system";

export type ResolvedTheme = "day" | "sepia" | "night";

export type ThemeTokens = {
  bg: string;
  text: string;
  accent: string;
  muted: string;
  border: string;
  surface: string;
  codeBg: string;
  toolbarBg: string;
};

/**
 * Default ClearRead reader themes (D-THEME-1).
 * Accents lightly mapped toward Rebeautifier: Day keeps calm blue-slate,
 * Sepia uses amber-adjacent warmth, Night keeps sky on slate surfaces.
 */
export const THEME_TOKENS: Record<ResolvedTheme, ThemeTokens> = {
  day: {
    bg: "#F9F6F0",
    text: "#2B2B2B",
    accent: "#2563EB",
    muted: "#5C5C5C",
    border: "#D9D2C5",
    surface: "#F4F0EA",
    codeBg: "#EDE7DB",
    toolbarBg: "rgba(249, 246, 240, 0.92)",
  },
  sepia: {
    bg: "#EFE4D0",
    text: "#4A3B32",
    accent: "#D97706",
    muted: "#6B5648",
    border: "#D4C2A5",
    surface: "#E6D7BC",
    codeBg: "#E0D0B4",
    toolbarBg: "rgba(239, 228, 208, 0.94)",
  },
  night: {
    bg: "#181A1B",
    text: "#E2E8F0",
    accent: "#38BDF8",
    muted: "#94A3B8",
    border: "#334155",
    surface: "#1E293B",
    codeBg: "#0F172A",
    toolbarBg: "rgba(24, 26, 27, 0.94)",
  },
};

export function resolveTheme(id: ReaderThemeId, prefersDark: boolean): ResolvedTheme {
  if (id === "system") return prefersDark ? "night" : "day";
  return id;
}

export function applyThemeTokens(root: HTMLElement, theme: ResolvedTheme) {
  const t = THEME_TOKENS[theme];
  root.style.setProperty("--reader-bg", t.bg);
  root.style.setProperty("--reader-text", t.text);
  root.style.setProperty("--reader-accent", t.accent);
  root.style.setProperty("--reader-muted", t.muted);
  root.style.setProperty("--reader-border", t.border);
  root.style.setProperty("--reader-surface", t.surface);
  root.style.setProperty("--reader-code-bg", t.codeBg);
  root.style.setProperty("--reader-toolbar-bg", t.toolbarBg);
  root.dataset.readerTheme = theme;
}
