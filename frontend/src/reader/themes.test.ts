import { describe, expect, it } from "vitest";
import { resolveTheme, THEME_TOKENS } from "./themes";

describe("reader themes", () => {
  it("resolves system to day/night", () => {
    expect(resolveTheme("system", false)).toBe("day");
    expect(resolveTheme("system", true)).toBe("night");
    expect(resolveTheme("sepia", true)).toBe("sepia");
  });

  it("never uses pure black or white body tokens", () => {
    for (const t of Object.values(THEME_TOKENS)) {
      expect(t.bg.toLowerCase()).not.toBe("#000000");
      expect(t.bg.toLowerCase()).not.toBe("#000");
      expect(t.text.toLowerCase()).not.toBe("#000000");
      expect(t.text.toLowerCase()).not.toBe("#ffffff");
      expect(t.text.toLowerCase()).not.toBe("#fff");
    }
  });
});
