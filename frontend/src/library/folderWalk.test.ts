import { describe, expect, it } from "vitest";
import {
  DEFAULT_MAX_SCAN_DEPTH,
  DEFAULT_MAX_SCAN_FILES,
  filterFlatRelativeHits,
  isWithinMaxDepth,
  pathHasSkippedSegment,
  relativePathDepth,
  walkMemoryTree,
  type MemoryDirNode,
  type WalkHit,
} from "./folderWalk";
import { shouldSkipDirectoryName } from "./fileFilter";

describe("relativePathDepth / isWithinMaxDepth", () => {
  it("counts subdirectory nesting under the preferred root", () => {
    expect(relativePathDepth("readme.md")).toBe(0);
    expect(relativePathDepth("docs/readme.md")).toBe(1);
    expect(relativePathDepth("a/b/c/d.md")).toBe(3);
  });

  it("enforces max depth", () => {
    expect(isWithinMaxDepth("a/b/file.md", 2)).toBe(true);
    expect(isWithinMaxDepth("a/b/c/file.md", 2)).toBe(false);
    expect(isWithinMaxDepth("file.md", DEFAULT_MAX_SCAN_DEPTH)).toBe(true);
  });
});

describe("ignore rules", () => {
  it("skips node_modules, .git, and other build dirs", () => {
    expect(shouldSkipDirectoryName("node_modules")).toBe(true);
    expect(shouldSkipDirectoryName(".git")).toBe(true);
    expect(shouldSkipDirectoryName("dist")).toBe(true);
    expect(shouldSkipDirectoryName("src")).toBe(false);
  });

  it("detects skipped segments in relative paths", () => {
    expect(pathHasSkippedSegment("src/a.md")).toBe(false);
    expect(pathHasSkippedSegment("node_modules/pkg/index.js")).toBe(true);
    expect(pathHasSkippedSegment(".git/config")).toBe(true);
  });
});

describe("walkMemoryTree (recursive to leaves)", () => {
  const tree: MemoryDirNode[] = [
    { name: "root.md", kind: "file", size: 10 },
    {
      name: "docs",
      kind: "directory",
      children: [
        { name: "guide.md", kind: "file", size: 20 },
        {
          name: "deep",
          kind: "directory",
          children: [{ name: "leaf.md", kind: "file", size: 30 }],
        },
      ],
    },
    {
      name: "node_modules",
      kind: "directory",
      children: [{ name: "skip.js", kind: "file", size: 40 }],
    },
    {
      name: ".git",
      kind: "directory",
      children: [{ name: "config", kind: "file", size: 5 }],
    },
  ];

  it("walks all subfolders to the leaves and skips ignored dirs", () => {
    const hits = walkMemoryTree(tree);
    const paths = hits.map((h) => h.relativePath).sort();
    expect(paths).toEqual(["docs/deep/leaf.md", "docs/guide.md", "root.md"]);
  });

  it("stops descending past maxDepth", () => {
    const hits = walkMemoryTree(tree, { maxDepth: 1 });
    const paths = hits.map((h) => h.relativePath).sort();
    expect(paths).toEqual(["docs/guide.md", "root.md"]);
    expect(paths).not.toContain("docs/deep/leaf.md");
  });

  it("caps maxFiles", () => {
    const many: MemoryDirNode[] = Array.from({ length: 10 }, (_, i) => ({
      name: `f${i}.md`,
      kind: "file" as const,
      size: 1,
    }));
    const hits = walkMemoryTree(many, { maxFiles: 3 });
    expect(hits).toHaveLength(3);
  });

  it("defaults stay within sane product limits", () => {
    expect(DEFAULT_MAX_SCAN_DEPTH).toBe(20);
    expect(DEFAULT_MAX_SCAN_FILES).toBe(5000);
  });
});

describe("filterFlatRelativeHits (webkitdirectory)", () => {
  const hits: WalkHit[] = [
    { relativePath: "a.md", name: "a.md", size: 1, type: "" },
    { relativePath: "sub/b.md", name: "b.md", size: 1, type: "" },
    { relativePath: "node_modules/x.js", name: "x.js", size: 1, type: "" },
    { relativePath: "d1/d2/d3/deep.md", name: "deep.md", size: 1, type: "" },
  ];

  it("applies ignore + depth + maxFiles like the FS Access walk", () => {
    const filtered = filterFlatRelativeHits(hits, { maxDepth: 2, maxFiles: 10 });
    const paths = filtered.map((h) => h.relativePath).sort();
    expect(paths).toEqual(["a.md", "d1/d2/d3/deep.md", "sub/b.md"].filter((p) => isWithinMaxDepth(p, 2)).sort());
    expect(paths).not.toContain("node_modules/x.js");
    // depth 3 > maxDepth 2
    expect(paths).not.toContain("d1/d2/d3/deep.md");
  });
});
