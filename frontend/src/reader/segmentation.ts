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

const KNOWN_TYPE_ALIASES: Record<string, string> = {
  heading: "heading",
  headings: "heading",
  h1: "heading",
  h2: "heading",
  h3: "heading",
  paragraph: "paragraph",
  p: "paragraph",
  para: "paragraph",
  codeblock: "codeBlock",
  code: "codeBlock",
  pre: "codeBlock",
  blockquote: "blockquote",
  quote: "blockquote",
  thematicbreak: "thematicBreak",
  hr: "thematicBreak",
  horizontalrule: "thematicBreak",
  bulletlist: "bulletList",
  ul: "bulletList",
  orderedlist: "orderedList",
  ol: "orderedList",
  datatable: "dataTable",
  table: "dataTable",
  treenode: "treeNode",
  tree: "treeNode",
  formattedsource: "formattedSource",
  source: "formattedSource",
};

/** Normalize known block type spellings so filters and the renderer agree. */
export function normalizeBlockType(type: unknown): string {
  const raw = String(type ?? "").trim();
  if (!raw) return "";
  const key = raw.toLowerCase().replace(/[\s_-]+/g, "");
  return KNOWN_TYPE_ALIASES[key] ?? raw;
}

/**
 * Coerce ClearDoc.body to a block array (never trust remote/local shape).
 * Accepts array bodies and common wrappers ({ blocks }, { nodes }, nested { body }).
 */
export function coerceBody(doc: ClearDoc | null | undefined): Record<string, unknown>[] {
  return coerceBlocks(doc?.body as unknown);
}

function coerceBlocks(raw: unknown): Record<string, unknown>[] {
  if (Array.isArray(raw)) {
    return (raw as Record<string, unknown>[]).map(normalizeBlockNode);
  }
  if (raw && typeof raw === "object") {
    const o = raw as Record<string, unknown>;
    for (const key of ["blocks", "nodes", "body", "children", "items"] as const) {
      if (Array.isArray(o[key])) {
        return (o[key] as Record<string, unknown>[]).map(normalizeBlockNode);
      }
    }
    // Single block object with a type field
    if (o.type != null) {
      return [normalizeBlockNode(o)];
    }
  }
  if (typeof raw === "string" && raw.trim()) {
    try {
      return coerceBlocks(JSON.parse(raw));
    } catch {
      return EMPTY_BODY;
    }
  }
  return EMPTY_BODY;
}

function normalizeBlockNode(node: Record<string, unknown>): Record<string, unknown> {
  const normalized = normalizeBlockType(node?.type);
  if (!normalized || normalized === String(node?.type ?? "")) return node;
  return { ...node, type: normalized };
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

  const filtered = body.filter((n) => allow.has(normalizeBlockType(n?.type)));
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
