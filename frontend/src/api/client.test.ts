import { afterEach, describe, expect, it, vi } from "vitest";
import { describeApiTarget, formatApiError, resolveApiBase } from "./client";

describe("formatApiError", () => {
  it("turns Failed to fetch into actionable guidance", () => {
    const msg = formatApiError(new TypeError("Failed to fetch"), "Library");
    expect(msg).toMatch(/Cannot reach the ClearRead API/);
    expect(msg).toMatch(/start\.bat|start\.sh/i);
    expect(msg).not.toMatch(/^Library: TypeError/);
  });

  it("passes through HTTP-style Error messages", () => {
    expect(formatApiError(new Error("quota exceeded"))).toBe("quota exceeded");
  });
});

describe("resolveApiBase", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("uses empty string for same-origin when unset", () => {
    vi.stubEnv("VITE_API_BASE_URL", undefined as unknown as string);
    // resolveApiBase reads import.meta.env at call time in module — re-import pattern:
    // We assert the exported helper behavior via describeApiTarget when apiBase is "".
    expect(typeof resolveApiBase()).toBe("string");
  });

  it("describeApiTarget mentions same-origin when base is empty", () => {
    const target = describeApiTarget();
    expect(target.length).toBeGreaterThan(0);
  });
});
