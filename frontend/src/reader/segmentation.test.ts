import { describe, expect, it } from "vitest";
import type { ClearDoc } from "./ClearDocView";
import { coerceBody, segmentBody, segmentOutline } from "./segmentation";

const doc: ClearDoc = {
  metadata: { title: "T" },
  outline: [
    { level: 1, text: "A", nodeId: "n1" },
    { level: 2, text: "B", nodeId: "n2" },
    { level: 3, text: "C", nodeId: "n3" },
  ],
  warnings: [],
  body: [
    { type: "heading", level: 1, nodeId: "n1" },
    { type: "paragraph", nodeId: "p1" },
    { type: "blockquote", nodeId: "q1" },
    { type: "thematicBreak", nodeId: "hr1" },
    { type: "dataTable", nodeId: "t1", headers: ["A"], rows: [["1"]] },
    { type: "treeNode", nodeId: "j1", label: "root" },
    { type: "formattedSource", nodeId: "s1", text: "x = 1" },
  ],
};

describe("segmentation", () => {
  it("conservative drops blockquotes", () => {
    const body = segmentBody(doc, "conservative");
    expect(body.every((n) => n.type !== "blockquote")).toBe(true);
    expect(segmentOutline(doc, "conservative").every((o) => o.level <= 2)).toBe(true);
  });

  it("conservative keeps structured-data renderers", () => {
    const body = segmentBody(doc, "conservative");
    expect(body.some((n) => n.type === "dataTable")).toBe(true);
    expect(body.some((n) => n.type === "treeNode")).toBe(true);
    expect(body.some((n) => n.type === "formattedSource")).toBe(true);
  });

  it("detailed keeps full body", () => {
    expect(segmentBody(doc, "detailed")).toHaveLength(doc.body.length);
  });

  it("detailed keeps unknown node types", () => {
    const weird: ClearDoc = {
      ...doc,
      body: [{ type: "customWidget", nodeId: "x1" }, ...doc.body],
    };
    expect(segmentBody(weird, "detailed").some((n) => n.type === "customWidget")).toBe(true);
  });

  it("detailed never empties a non-empty ClearDoc (regression)", () => {
    const nonEmpty: ClearDoc = {
      ...doc,
      body: [
        { type: "mysteryBlock", nodeId: "m1", children: [{ type: "text", value: "keep me" }] },
        { type: "paragraph", nodeId: "p2", children: [{ type: "text", value: "also keep" }] },
      ],
    };
    const out = segmentBody(nonEmpty, "detailed");
    expect(out.length).toBeGreaterThan(0);
    expect(out).toHaveLength(nonEmpty.body.length);
    expect(coerceBody(nonEmpty).length).toBeGreaterThan(0);
  });

  it("detailed accepts mis-cased / aliased mode keys", () => {
    expect(segmentBody(doc, "Detailed")).toHaveLength(doc.body.length);
    expect(segmentBody(doc, "DETAIL")).toHaveLength(doc.body.length);
    expect(segmentBody(doc, "full")).toHaveLength(doc.body.length);
  });

  it("balanced falls back to full body when filter would empty a non-empty doc", () => {
    const onlyUnknown: ClearDoc = {
      ...doc,
      body: [{ type: "mysteryBlock", nodeId: "m1" }],
    };
    expect(segmentBody(onlyUnknown, "balanced")).toHaveLength(1);
    expect(segmentBody(onlyUnknown, "conservative")).toHaveLength(1);
  });

  it("coerceBody tolerates non-array body", () => {
    const broken = { ...doc, body: null as unknown as ClearDoc["body"] };
    expect(coerceBody(broken)).toEqual([]);
    expect(segmentBody(broken, "detailed")).toEqual([]);
  });
});
