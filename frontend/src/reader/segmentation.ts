import type { ClearDoc } from "./ClearDocView";
import type { SegmentationMode } from "./preferences";

/**
 * View-layer segmentation — never mutates stored ClearDoc / originals.
 * Conservative: headings + paragraphs + lists + code (MVP core)
 * Balanced: also blockquotes / thematic breaks
 * Detailed: full body including inferred badges emphasis
 */
export function segmentBody(doc: ClearDoc, mode: SegmentationMode): Record<string, unknown>[] {
  const body = doc.body ?? [];
  if (mode === "detailed") return body;

  const allow = new Set(
    mode === "conservative"
      ? ["heading", "paragraph", "bulletList", "orderedList", "codeBlock"]
      : ["heading", "paragraph", "bulletList", "orderedList", "codeBlock", "blockquote", "thematicBreak"]
  );

  return body.filter((n) => allow.has(String(n.type)));
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
