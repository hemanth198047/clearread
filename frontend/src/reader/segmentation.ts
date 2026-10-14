import type { ClearDoc } from "./ClearDocView";
import type { SegmentationMode } from "./preferences";
import { normalizeSegmentationMode } from "./preferences";

/**
 * View-layer segmentation — never mutates stored ClearDoc / originals.
 * Conservative: headings + paragraphs + lists + code (MVP core)
 * Balanced: also blockquotes / thematic breaks
 * Detailed: full body including inferred badges emphasis — never drops nodes
 */
/** Always keep structured-data renderers; segmentation only filters prose chrome. */
const STRUCTURED = ["dataTable", "treeNode", "formattedSource"] as const;

const CORE_PROSE = ["heading", "paragraph", "bulletList", "orderedList", "codeBlock"] as const;
const BALANCED_EXTRA = ["blockquote", "thematicBreak"] as const;

const EMPTY_BODY: Record<string, unknown>[] = [];

/** Coerce ClearDoc.body to a block array (never trust remote/local shape). */
export function coerceBody(doc: ClearDoc | null | undefined): Record<string, unknown>[] {
  const raw = doc?.body as unknown;
  if (Array.isArray(raw)) return raw as Record<string, unknown>[];
  return EMPTY_BODY;
}

export function segmentBody(
  doc: ClearDoc,
  mode: SegmentationMode | string | null | undefined
): Record<string, unknown>[] {
  const body = coerceBody(doc);
  const normalized = normalizeSegmentationMode(mode);

  // Detailed: always return the full body (including unknown / inferred node types).
  if (normalized === "detailed") return body.slice();

  const allow = new Set<string>([
    ...CORE_PROSE,
    ...(normalized === "balanced" ? BALANCED_EXTRA : []),
    ...STRUCTURED,
  ]);

  const filtered = body.filter((n) => allow.has(String(n?.type ?? "")));
  // Safety: never render a blank reader when the source had content — fall back to full body.
  if (filtered.length === 0 && body.length > 0) return body.slice();
  return filtered;
}

export function segmentOutline(doc: ClearDoc, mode: SegmentationMode | string | null | undefined) {
  const normalized = normalizeSegmentationMode(mode);
  const outline = Array.isArray(doc.outline) ? doc.outline : [];
  if (normalized === "detailed" || normalized === "balanced") return outline;
  // Conservative: only H1–H2 for quieter nav
  return outline.filter((o) => o.level <= 2);
}

export const SEGMENTATION_LABELS: Record<SegmentationMode, string> = {
  conservative: "Conservative",
  balanced: "Balanced",
  detailed: "Detailed",
};
