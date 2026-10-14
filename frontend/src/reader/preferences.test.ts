import { describe, expect, it } from "vitest";
import { mergePreferences, normalizeSegmentationMode, preferencesEqual, DEFAULT_PREFERENCES } from "./preferences";

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

  it("preferencesEqual is true for identical values and false when segmentation differs", () => {
    const a = mergePreferences({ segmentation: "detailed" });
    const b = mergePreferences({ segmentation: "Detailed" as "detailed" });
    expect(preferencesEqual(a, b)).toBe(true);
    expect(preferencesEqual(a, DEFAULT_PREFERENCES)).toBe(false);
  });
});
