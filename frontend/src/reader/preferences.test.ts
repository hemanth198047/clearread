import { describe, expect, it } from "vitest";
import { mergePreferences, normalizeSegmentationMode } from "./preferences";

describe("normalizeSegmentationMode", () => {
  it("accepts canonical keys", () => {
    expect(normalizeSegmentationMode("detailed")).toBe("detailed");
    expect(normalizeSegmentationMode("balanced")).toBe("balanced");
    expect(normalizeSegmentationMode("conservative")).toBe("conservative");
  });

  it("normalizes mis-cased and aliased Detailed keys", () => {
    expect(normalizeSegmentationMode("Detailed")).toBe("detailed");
    expect(normalizeSegmentationMode("DETAIL")).toBe("detailed");
    expect(normalizeSegmentationMode("full")).toBe("detailed");
    expect(normalizeSegmentationMode("detail")).toBe("detailed");
  });

  it("mergePreferences coerces segmentation", () => {
    expect(mergePreferences({ segmentation: "Detailed" as "detailed" }).segmentation).toBe("detailed");
  });
});
