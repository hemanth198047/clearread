import { useEffect, useState } from "react";
import { apiFetch } from "../api/client";
import { useAuth } from "../auth/AuthProvider";

export type WorkspaceAiStatus = {
  loading: boolean;
  aiEnabled: boolean;
  aiProviderId: string;
  aiLocalOnly: boolean;
  aiAllowExternal: boolean;
  providerLabel: string;
  connected: boolean;
  detail: string;
  configurePath: string;
};

type TenantSettings = {
  aiEnabled?: boolean;
  aiProviderId?: string;
  aiLocalOnly?: boolean;
  aiAllowExternal?: boolean;
};

type AiConnection = {
  ollamaAvailable?: boolean;
  externalAvailable?: boolean;
  apiKeyConfigured?: boolean;
  ollamaModel?: string;
  externalModel?: string;
};

function providerLabel(id: string): string {
  switch (id) {
    case "ollama":
      return "Ollama";
    case "external":
      return "External";
    case "mock-local":
      return "Demo (mock-local)";
    default:
      return id || "—";
  }
}

function connectedFor(providerId: string, conn: AiConnection | null): boolean {
  if (providerId === "mock-local") return true;
  if (providerId === "ollama") return Boolean(conn?.ollamaAvailable);
  if (providerId === "external") return Boolean(conn?.externalAvailable);
  return false;
}

function detailFor(providerId: string, conn: AiConnection | null, connected: boolean): string {
  if (!connected) {
    if (providerId === "ollama") return "Ollama not configured";
    if (providerId === "external") return "External provider not configured";
    return "Provider not ready";
  }
  if (providerId === "ollama") return conn?.ollamaModel ? `Ollama · ${conn.ollamaModel}` : "Ollama connected";
  if (providerId === "external") {
    const model = conn?.externalModel || "model";
    const key = conn?.apiKeyConfigured ? "key saved" : "no key";
    return `External · ${model} · ${key}`;
  }
  return "Demo provider";
}

const DISABLED: WorkspaceAiStatus = {
  loading: false,
  aiEnabled: false,
  aiProviderId: "",
  aiLocalOnly: true,
  aiAllowExternal: false,
  providerLabel: "",
  connected: false,
  detail: "AI enrichment off",
  configurePath: "/admin#ai-provider",
};

/**
 * Workspace AI policy + connection status for main-app chrome (header / Settings / Home).
 * Readable by any signed-in workspace member via tenant settings + ai-connection GET.
 */
export function useWorkspaceAiStatus(): WorkspaceAiStatus {
  const auth = useAuth();
  const token = auth.getAccessToken();
  const signedIn = Boolean(auth.user || (auth.devBypass && token));
  const [status, setStatus] = useState<WorkspaceAiStatus>({ ...DISABLED, loading: signedIn });

  useEffect(() => {
    if (!signedIn || !token) {
      setStatus(DISABLED);
      return;
    }
    let cancelled = false;
    setStatus((prev) => ({ ...prev, loading: true }));
    (async () => {
      try {
        const me = (await apiFetch("/v1/me", token)) as { activeTenantId: string };
        const [settings, conn] = await Promise.all([
          apiFetch(`/v1/tenants/${me.activeTenantId}/settings`, token) as Promise<TenantSettings>,
          apiFetch(`/v1/tenants/${me.activeTenantId}/ai-connection`, token) as Promise<AiConnection>,
        ]);
        if (cancelled) return;
        const aiEnabled = Boolean(settings.aiEnabled);
        const aiProviderId = settings.aiProviderId || "mock-local";
        const connected = connectedFor(aiProviderId, conn);
        setStatus({
          loading: false,
          aiEnabled,
          aiProviderId,
          aiLocalOnly: Boolean(settings.aiLocalOnly),
          aiAllowExternal: Boolean(settings.aiAllowExternal),
          providerLabel: providerLabel(aiProviderId),
          connected,
          detail: aiEnabled
            ? detailFor(aiProviderId, conn, connected)
            : "AI enrichment off",
          configurePath: "/admin#ai-provider",
        });
      } catch {
        if (!cancelled) {
          setStatus({ ...DISABLED, loading: false, detail: "AI status unavailable" });
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [signedIn, token]);

  return status;
}
