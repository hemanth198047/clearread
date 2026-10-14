import { shouldSkipDirectoryName } from "./fileFilter";

/** Max directory nesting under a preferred root (root = depth 0). */
export const DEFAULT_MAX_SCAN_DEPTH = 20;

/** Cap on files collected before filtering by extension/size. */
export const DEFAULT_MAX_SCAN_FILES = 5000;

export type WalkHit = {
  relativePath: string;
  name: string;
  size: number;
  type: string;
};

export type WalkLimits = {
  maxDepth?: number;
  maxFiles?: number;
};

/**
 * Depth of a relative path under the preferred root.
 * `notes/a.md` → 1 (one subdirectory); `a.md` → 0.
 */
export function relativePathDepth(relativePath: string): number {
  const parts = relativePath.split("/").filter(Boolean);
  if (parts.length <= 1) return 0;
  return parts.length - 1;
}

/** True when the file's containing folder depth is within the limit. */
export function isWithinMaxDepth(relativePath: string, maxDepth: number = DEFAULT_MAX_SCAN_DEPTH): boolean {
  return relativePathDepth(relativePath) <= maxDepth;
}

/**
 * True when any path segment (except the filename) is an ignored directory.
 */
export function pathHasSkippedSegment(relativePath: string): boolean {
  const segs = relativePath.split("/").filter(Boolean);
  if (segs.length <= 1) return false;
  return segs.slice(0, -1).some((s) => shouldSkipDirectoryName(s));
}

/**
 * Pure recursive walk over an in-memory directory tree (unit tests + shared limits).
 * Mirrors File System Access walk: skip ignore dirs, stop at max depth / max files.
 */
export type MemoryDirNode = {
  name: string;
  kind: "file" | "directory";
  size?: number;
  type?: string;
  children?: MemoryDirNode[];
};

export function walkMemoryTree(
  rootChildren: MemoryDirNode[],
  limits: WalkLimits = {}
): WalkHit[] {
  const maxDepth = limits.maxDepth ?? DEFAULT_MAX_SCAN_DEPTH;
  const maxFiles = limits.maxFiles ?? DEFAULT_MAX_SCAN_FILES;
  const out: WalkHit[] = [];

  function visit(nodes: MemoryDirNode[], prefix: string, depth: number): void {
    for (const node of nodes) {
      if (out.length >= maxFiles) return;
      if (node.kind === "directory") {
        if (shouldSkipDirectoryName(node.name)) continue;
        if (depth >= maxDepth) continue;
        const next = prefix ? `${prefix}/${node.name}` : node.name;
        visit(node.children ?? [], next, depth + 1);
      } else if (node.kind === "file") {
        const relativePath = prefix ? `${prefix}/${node.name}` : node.name;
        out.push({
          relativePath,
          name: node.name,
          size: node.size ?? 0,
          type: node.type ?? "",
        });
      }
    }
  }

  visit(rootChildren, "", 0);
  return out;
}

/**
 * Filter a flat picker list (webkitdirectory) with the same ignore + depth + max-files rules.
 */
export function filterFlatRelativeHits(hits: WalkHit[], limits: WalkLimits = {}): WalkHit[] {
  const maxDepth = limits.maxDepth ?? DEFAULT_MAX_SCAN_DEPTH;
  const maxFiles = limits.maxFiles ?? DEFAULT_MAX_SCAN_FILES;
  const out: WalkHit[] = [];
  for (const hit of hits) {
    if (out.length >= maxFiles) break;
    if (pathHasSkippedSegment(hit.relativePath)) continue;
    if (!isWithinMaxDepth(hit.relativePath, maxDepth)) continue;
    out.push(hit);
  }
  return out;
}
