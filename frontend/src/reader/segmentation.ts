import type { ClearDoc } from "./ClearDocView";
import type { SegmentationMode } from "./preferences";

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

export function segmentBody(doc: ClearDoc, mode: SegmentationMode): Record<string, unknown>[] {
  const body = Array.isArray(doc.body) ? doc.body : [];
  // Detailed: always return the full body (including unknown / inferred node types).
  if (mode === "detailed") return body.slice();

  const allow = new Set<string>([
    ...CORE_PROSE,
    ...(mode === "balanced" ? BALANCED_EXTRA : []),
    ...STRUCTURED,
  ]);

  const filtered = body.filter((n) => allow.has(String(n?.type ?? "")));
  // Safety: never render a blank reader when the source had content — fall back to full body.
  if (filtered.length === 0 && body.length > 0) return body.slice();
  return filtered;
}

export function segmentOutline(doc: ClearDoc, mode: SegmentationMode) {
  if (mode === "detailed" || mode === "balanced") return doc.outline;
  // Conservative: only H1–H2 for quieter nav
  return doc.outline.filter((o) => o.level <= 2);
}

export const SEGMENTATION_LABELS: Record<SegmentationMode, string> = {
  conservative: "Conservative",
  balanced: "Balanced",
  detailed: "Detailed",
};
