import { describe, expect, it } from "vitest";
import {
  applyPreset,
  matchPresetFromBaseUrl,
  presetById,
} from "./externalProviderPresets";

describe("externalProviderPresets", () => {
  it("matches known provider URLs", () => {
    expect(matchPresetFromBaseUrl("https://api.openai.com/v1/chat/completions")).toBe("openai");
    expect(matchPresetFromBaseUrl("https://api.anthropic.com/v1/messages")).toBe("anthropic");
    expect(
      matchPresetFromBaseUrl(
        "https://my.openai.azure.com/openai/deployments/x/chat/completions?api-version=2024-08-01-preview"
      )
    ).toBe("azure");
    expect(matchPresetFromBaseUrl("https://api.groq.com/openai/v1/chat/completions")).toBe("groq");
    expect(matchPresetFromBaseUrl("https://openrouter.ai/api/v1/chat/completions")).toBe("openrouter");
    expect(matchPresetFromBaseUrl("https://api.example.com/v1/enrich")).toBe("custom");
    expect(matchPresetFromBaseUrl("")).toBe("custom");
  });

  it("applyPreset fills OpenAI defaults", () => {
    const next = applyPreset("openai", { externalBaseUrl: "", externalModel: "" });
    expect(next.externalBaseUrl).toContain("api.openai.com");
    expect(next.externalModel).toBe("gpt-4o-mini");
    expect(next.expandAdvanced).toBe(false);
  });

  it("applyPreset expands advanced for Azure", () => {
    const next = applyPreset("azure", { externalBaseUrl: "", externalModel: "gpt-4o" });
    expect(next.expandAdvanced).toBe(true);
    expect(next.externalModel).toBe("gpt-4o");
  });

  it("presetById falls back to custom", () => {
    expect(presetById("nope").id).toBe("custom");
  });
});
