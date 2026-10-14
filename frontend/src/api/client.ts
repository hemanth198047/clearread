/**
 * API base URL resolution.
 * Prefer same-origin ("") so Vite proxy (dev) and nginx → api (Compose) handle /v1.
 * Absolute VITE_API_BASE_URL remains supported for split deploys.
 */
export function resolveApiBase(): string {
  const raw = import.meta.env.VITE_API_BASE_URL;
  if (raw === "" || raw === "/") return "";
  if (typeof raw === "string" && raw.trim().length > 0) {
    return raw.trim().replace(/\/$/, "");
  }
  return "";
}

export const apiBase = resolveApiBase();

export function describeApiTarget(): string {
  if (apiBase) return apiBase;
  if (typeof window !== "undefined") return `${window.location.origin} (same-origin /v1)`;
  return "same-origin /v1";
}

/** True when the API removed a document whose original object was gone (410 purge path). */
export function isPurgedUnavailableDocument(err: unknown): boolean {
  const msg = err instanceof Error ? err.message : String(err);
  return /document removed|original was unavailable|410/i.test(msg);
}

/** Human-friendly message for network / API-down failures (not raw TypeError). */
export function formatApiError(err: unknown, context?: string): string {
  const prefix = context ? `${context}: ` : "";
  if (err instanceof TypeError || (err instanceof Error && /failed to fetch|networkerror|load failed/i.test(err.message))) {
    const portHint =
      typeof window !== "undefined"
        ? `Web UI is at ${window.location.origin}. API should be reachable via ${describeApiTarget()}.`
        : `API should be reachable via ${describeApiTarget()}.`;
    return (
      `${prefix}Cannot reach the ClearRead API. ${portHint} ` +
      `Start the stack (./start.bat or scripts/start.sh), confirm CLEARREAD_API_PORT, ` +
      `and that the web image was rebuilt after port changes. ` +
      `If you set VITE_API_BASE_URL, it must match the API host/port the browser can open.`
    );
  }
  if (err instanceof Error) return `${prefix}${err.message}`;
  return `${prefix}${String(err)}`;
}

export async function apiFetch(path: string, token: string | null, init: RequestInit = {}, tenantId?: string) {
  const headers = new Headers(init.headers);
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  if (tenantId) {
    headers.set("X-Tenant-Id", tenantId);
  }
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const url = `${apiBase}${path.startsWith("/") ? path : `/${path}`}`;
  let res: Response;
  try {
    res = await fetch(url, { ...init, headers });
  } catch (err) {
    throw new Error(formatApiError(err));
  }
  if (!res.ok) {
    const text = await res.text();
    let detail = text || res.statusText;
    try {
      const json = JSON.parse(text) as { message?: string; detail?: string; title?: string };
      detail = json.message || json.detail || json.title || detail;
    } catch {
      /* plain text */
    }
    throw new Error(detail || `HTTP ${res.status}`);
  }
  const contentType = res.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    return res.json();
  }
  return res.arrayBuffer();
}
