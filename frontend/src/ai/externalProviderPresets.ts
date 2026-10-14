/**
 * Curated external AI provider presets for Admin connection UI.
 * Selecting a preset fills base URL + model defaults; user mainly pastes an API key.
 */

export type ExternalProviderPresetId =
  | "openai"
  | "anthropic"
  | "azure"
  | "groq"
  | "openrouter"
  | "custom";

export type ExternalProviderPreset = {
  id: ExternalProviderPresetId;
  label: string;
  description: string;
  /** Full request URL (OpenAI chat, Anthropic messages, or ClearRead custom enrich). */
  baseUrl: string;
  /** Placeholder when baseUrl is empty (Azure / custom). */
  baseUrlPlaceholder?: string;
  models: string[];
  defaultModel: string;
  /** Open advanced URL/model fields by default (Azure needs a deployment URL). */
  expandAdvancedByDefault?: boolean;
};

export const EXTERNAL_PROVIDER_PRESETS: ExternalProviderPreset[] = [
  {
    id: "openai",
    label: "OpenAI",
    description: "GPT models via OpenAI Chat Completions",
    baseUrl: "https://api.openai.com/v1/chat/completions",
    models: ["gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1"],
    defaultModel: "gpt-4o-mini",
  },
  {
    id: "anthropic",
    label: "Anthropic",
    description: "Claude models via Anthropic Messages API",
    baseUrl: "https://api.anthropic.com/v1/messages",
    models: ["claude-3-5-haiku-latest", "claude-sonnet-4-20250514", "claude-3-5-sonnet-latest"],
    defaultModel: "claude-3-5-haiku-latest",
  },
  {
    id: "azure",
    label: "Azure OpenAI",
    description: "Azure deployment chat completions URL",
    baseUrl: "",
    baseUrlPlaceholder:
      "https://YOUR_RESOURCE.openai.azure.com/openai/deployments/YOUR_DEPLOYMENT/chat/completions?api-version=2024-08-01-preview",
    models: ["gpt-4o-mini", "gpt-4o", "gpt-35-turbo"],
    defaultModel: "gpt-4o-mini",
    expandAdvancedByDefault: true,
  },
  {
    id: "groq",
    label: "Groq",
    description: "Fast OpenAI-compatible inference",
    baseUrl: "https://api.groq.com/openai/v1/chat/completions",
    models: ["llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768"],
    defaultModel: "llama-3.3-70b-versatile",
  },
  {
    id: "openrouter",
    label: "OpenRouter",
    description: "Multi-provider router (OpenAI-compatible)",
    baseUrl: "https://openrouter.ai/api/v1/chat/completions",
    models: [
      "openai/gpt-4o-mini",
      "anthropic/claude-3.5-sonnet",
      "meta-llama/llama-3.3-70b-instruct",
      "google/gemini-2.0-flash-001",
    ],
    defaultModel: "openai/gpt-4o-mini",
  },
  {
    id: "custom",
    label: "Custom HTTP",
    description: "Any OpenAI-compatible chat URL or ClearRead enrich endpoint",
    baseUrl: "",
    baseUrlPlaceholder: "https://api.example.com/v1/chat/completions",
    models: ["gpt-4o-mini"],
    defaultModel: "gpt-4o-mini",
    expandAdvancedByDefault: true,
  },
];

export function presetById(id: string | null | undefined): ExternalProviderPreset {
  return EXTERNAL_PROVIDER_PRESETS.find((p) => p.id === id) ?? EXTERNAL_PROVIDER_PRESETS[EXTERNAL_PROVIDER_PRESETS.length - 1];
}

/** Infer preset from a saved base URL (best-effort). */
export function matchPresetFromBaseUrl(baseUrl: string | null | undefined): ExternalProviderPresetId {
  const u = (baseUrl ?? "").trim().toLowerCase();
  if (!u) return "custom";
  if (u.includes("api.openai.com")) return "openai";
  if (u.includes("api.anthropic.com")) return "anthropic";
  if (u.includes("openai.azure.com") || u.includes("cognitiveservices.azure.com")) return "azure";
  if (u.includes("api.groq.com")) return "groq";
  if (u.includes("openrouter.ai")) return "openrouter";
  return "custom";
}

export function applyPreset(
  presetId: ExternalProviderPresetId,
  current: { externalBaseUrl: string; externalModel: string }
): { externalBaseUrl: string; externalModel: string; expandAdvanced: boolean } {
  const preset = presetById(presetId);
  const nextUrl = preset.baseUrl || current.externalBaseUrl;
  const modelInList = preset.models.includes(current.externalModel);
  return {
    externalBaseUrl: nextUrl,
    externalModel: modelInList ? current.externalModel : preset.defaultModel,
    expandAdvanced: Boolean(preset.expandAdvancedByDefault),
  };
}
