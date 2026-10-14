import { FormEvent, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  EXTERNAL_PROVIDER_PRESETS,
  applyPreset,
  matchPresetFromBaseUrl,
  presetById,
  type ExternalProviderPresetId,
} from "../ai/externalProviderPresets";
import { apiFetch, formatApiError } from "../api/client";
import { useAuth } from "../auth/AuthProvider";

type Settings = {
  sharingEnabled: boolean;
  anonymousPreviewEnabled: boolean;
  retentionDays: number | null;
  maxUploadBytes: number;
  retentionNote: string;
  aiEnabled: boolean;
  aiLocalOnly: boolean;
  aiAllowExternal: boolean;
  aiProviderId: string;
  aiDailyQuota: number;
  aiMaxRetries: number;
};

type AiConnection = {
  ollamaEnabled: boolean;
  ollamaBaseUrl: string;
  ollamaModel: string;
  ollamaAvailable: boolean;
  ollamaSource: string;
  externalEnabled: boolean;
  externalBaseUrl: string;
  externalModel: string;
  externalAvailable: boolean;
  externalSource: string;
  apiKeyConfigured: boolean;
  apiKeyHint: string;
  defaults?: {
    ollamaBaseUrl?: string;
    ollamaModel?: string;
    externalBaseUrl?: string;
    externalModel?: string;
  };
};

type Job = { id: string; type: string; status: string; documentId: string; lastError: string };

type Invite = {
  id: string;
  email: string;
  role: string;
  status: string;
  inviteUrl: string;
  expiresAt: string;
  note: string;
};

export function AdminPage() {
  const auth = useAuth();
  const token = auth.getAccessToken();
  const [tenantId, setTenantId] = useState("");
  const [role, setRole] = useState("");
  const [settings, setSettings] = useState<Settings | null>(null);
  const [aiConnection, setAiConnection] = useState<AiConnection | null>(null);
  const [apiKeyDraft, setApiKeyDraft] = useState("");
  const [clearApiKey, setClearApiKey] = useState(false);
  const [externalPresetId, setExternalPresetId] = useState<ExternalProviderPresetId>("openai");
  const [showExternalAdvanced, setShowExternalAdvanced] = useState(false);
  const [customModelDraft, setCustomModelDraft] = useState(false);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [invites, setInvites] = useState<Invite[]>([]);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState("author");
  const [lastInviteUrl, setLastInviteUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [testBusy, setTestBusy] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);

  useEffect(() => {
    if (!token) return;
    (async () => {
      try {
        const me = (await apiFetch("/v1/me", token)) as { activeTenantId: string; activeRole: string };
        setTenantId(me.activeTenantId);
        setRole(me.activeRole);
        const s = (await apiFetch(`/v1/tenants/${me.activeTenantId}/settings`, token)) as Settings;
        setSettings(s);
        const conn = (await apiFetch(`/v1/tenants/${me.activeTenantId}/ai-connection`, token)) as AiConnection;
        setAiConnection(conn);
        const matched = matchPresetFromBaseUrl(conn.externalBaseUrl);
        setExternalPresetId(matched);
        const preset = presetById(matched);
        setShowExternalAdvanced(Boolean(preset.expandAdvancedByDefault) || matched === "custom");
        setCustomModelDraft(Boolean(conn.externalModel) && !preset.models.includes(conn.externalModel));
        if (me.activeRole === "admin") {
          const j = (await apiFetch(`/v1/tenants/${me.activeTenantId}/jobs`, token)) as Job[];
          setJobs(j);
          const inv = (await apiFetch(`/v1/tenants/${me.activeTenantId}/invites`, token)) as Invite[];
          setInvites(inv);
        }
      } catch (e) {
        setError(formatApiError(e, "Admin"));
      }
    })();
  }, [token]);

  async function createInvite(e: FormEvent) {
    e.preventDefault();
    if (!token || !tenantId || !inviteEmail.trim()) return;
    setMsg(null);
    setError(null);
    try {
      const created = (await apiFetch(`/v1/tenants/${tenantId}/invites`, token, {
        method: "POST",
        body: JSON.stringify({ email: inviteEmail.trim(), role: inviteRole }),
      })) as Invite;
      setLastInviteUrl(created.inviteUrl);
      setInvites((prev) => [created, ...prev]);
      setInviteEmail("");
      setMsg("Invite recorded. Copy the link — email is not sent.");
      try {
        await navigator.clipboard.writeText(created.inviteUrl);
        setMsg("Invite recorded and link copied to clipboard (no email sent).");
      } catch {
        /* clipboard may be unavailable */
      }
    } catch (err) {
      setError(formatApiError(err, "Invite"));
    }
  }

  async function saveSettings() {
    if (!token || !tenantId || !settings) return;
    setMsg(null);
    setError(null);
    try {
      const next = (await apiFetch(`/v1/tenants/${tenantId}/settings`, token, {
        method: "PUT",
        body: JSON.stringify(settings),
      })) as Settings;
      setSettings(next);
      setMsg("Settings saved");
    } catch (e) {
      setError(formatApiError(e, "Settings"));
    }
  }

  const externalPreset = useMemo(() => presetById(externalPresetId), [externalPresetId]);

  /** Keep policy form in sync when the user commits to an external provider in the connection UI. */
  function guidePolicyForExternal() {
    setSettings((prev) =>
      prev
        ? {
            ...prev,
            aiProviderId: "external",
            aiLocalOnly: false,
            aiAllowExternal: true,
            aiEnabled: true,
          }
        : prev
    );
  }

  function selectExternalPreset(id: ExternalProviderPresetId) {
    if (!aiConnection) return;
    setExternalPresetId(id);
    const next = applyPreset(id, aiConnection);
    setAiConnection({
      ...aiConnection,
      externalEnabled: true,
      externalBaseUrl: next.externalBaseUrl,
      externalModel: next.externalModel,
    });
    setShowExternalAdvanced(next.expandAdvanced);
    setCustomModelDraft(false);
    guidePolicyForExternal();
  }

  async function saveAiConnection() {
    if (!token || !tenantId || !aiConnection) return;
    setMsg(null);
    setError(null);
    setTestResult(null);
    try {
      const body: Record<string, unknown> = {
        ollamaEnabled: aiConnection.ollamaEnabled,
        ollamaBaseUrl: aiConnection.ollamaBaseUrl,
        ollamaModel: aiConnection.ollamaModel,
        externalEnabled: aiConnection.externalEnabled,
        externalBaseUrl: aiConnection.externalBaseUrl,
        externalModel: aiConnection.externalModel,
        clearApiKey,
        alignPolicy: true,
      };
      if (apiKeyDraft.trim()) {
        body.apiKey = apiKeyDraft.trim();
      }
      const next = (await apiFetch(`/v1/tenants/${tenantId}/ai-connection`, token, {
        method: "PUT",
        body: JSON.stringify(body),
      })) as AiConnection & {
        policyAligned?: {
          changed?: boolean;
          aiEnabled?: boolean;
          aiProviderId?: string;
          aiLocalOnly?: boolean;
          aiAllowExternal?: boolean;
        };
      };
      setAiConnection(next);
      setApiKeyDraft("");
      setClearApiKey(false);
      if (next.policyAligned?.changed) {
        setSettings((prev) =>
          prev
            ? {
                ...prev,
                aiEnabled: Boolean(next.policyAligned?.aiEnabled),
                aiProviderId: String(next.policyAligned?.aiProviderId || prev.aiProviderId),
                aiLocalOnly: Boolean(next.policyAligned?.aiLocalOnly),
                aiAllowExternal: Boolean(next.policyAligned?.aiAllowExternal),
              }
            : prev
        );
        setMsg(
          `AI connection saved · policy aligned to ${next.policyAligned.aiProviderId} (AI enabled, external allowed)`
        );
      } else {
        setMsg("AI provider connection saved");
      }
    } catch (e) {
      setError(formatApiError(e, "AI connection"));
    }
  }

  async function testAiConnection(target: "external" | "ollama" | "auto") {
    if (!token || !tenantId) return;
    setTestBusy(true);
    setTestResult(null);
    setError(null);
    try {
      const result = (await apiFetch(`/v1/tenants/${tenantId}/ai-connection/test`, token, {
        method: "POST",
        body: JSON.stringify({ target }),
      })) as { ok: boolean; message: string };
      setTestResult({ ok: Boolean(result.ok), message: result.message || (result.ok ? "OK" : "Failed") });
    } catch (e) {
      setTestResult({ ok: false, message: formatApiError(e, "Test connection") });
    } finally {
      setTestBusy(false);
    }
  }

  if (auth.loading) {
    return (
      <section className="panel">
        <div className="status-bar">
          <span className="status-dot busy" />
          <span>Checking session…</span>
        </div>
      </section>
    );
  }

  if (!auth.user && !auth.devBypass) {
    return (
      <section className="auth-stage">
        <div className="auth-stage-panel access-panel">
          <p className="page-eyebrow">Operators</p>
          <h1 className="auth-stage-brand">
            ClearRead<span>.</span>
          </h1>
          <h2>Workspace admin</h2>
          <p className="lede">Sign in as a workspace admin to manage settings, invites, and jobs.</p>
          <div className="access-actions">
            <button type="button" onClick={() => void auth.login()}>
              Sign in
            </button>
            <Link className="button secondary" to="/">
              Home
            </Link>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="panel">
      <div className="page-header" style={{ border: 0, padding: 0, margin: 0 }}>
        <div>
          <p className="page-eyebrow">Operators</p>
          <h1>Workspace admin</h1>
          <p className="lede">
            Sharing policy, retention, upload limit, optional AI enrichment, invites, and job inspection.
            {role ? ` Active role: ${role}.` : ""}
          </p>
        </div>
        <div className="page-header-actions">
          <Link className="button secondary" to="/library">
            Library
          </Link>
        </div>
      </div>
      <div className="status-bar">
        <span className={`status-dot${error ? " err" : ""}`} />
        <span>{tenantId ? `Tenant ${tenantId.slice(0, 8)}…` : "Loading tenant…"}</span>
        <span className="muted">{role || "role pending"}</span>
      </div>
      {error && (
        <p className="alert" role="alert">
          {error}
        </p>
      )}
      {msg && <p className="status-line">{msg}</p>}

      {role === "admin" && (
        <div className="invite-block" data-testid="invite-admin">
          <h2>Invite user</h2>
          <p className="muted">
            Records a pending invite and builds a deep link. No outbound email — copy the URL (or IdP invite instructions)
            and share it with the invitee. Production usually uses org IdP SSO.
          </p>
          <form className="admin-grid" onSubmit={(e) => void createInvite(e)}>
            <label>
              Email
              <input
                type="email"
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
                placeholder="colleague@example.com"
                required
              />
            </label>
            <label>
              Role
              <select value={inviteRole} onChange={(e) => setInviteRole(e.target.value)}>
                <option value="viewer">viewer</option>
                <option value="author">author</option>
                <option value="admin">admin</option>
              </select>
            </label>
            <p>
              <button type="submit">Create invite link</button>
            </p>
          </form>
          {lastInviteUrl && (
            <p className="invite-url">
              <code>{lastInviteUrl}</code>
            </p>
          )}
          {invites.length > 0 && (
            <ul className="library-list">
              {invites.slice(0, 10).map((i) => (
                <li key={i.id}>
                  <code>{i.email}</code> · {i.role} · {i.status}
                  <div className="muted">{i.inviteUrl}</div>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {settings && (
        <>
          <div className="admin-grid">
            <label className="check">
              <input
                type="checkbox"
                checked={settings.sharingEnabled}
                onChange={(e) => setSettings({ ...settings, sharingEnabled: e.target.checked })}
              />
              Sharing enabled
            </label>
            <label className="check">
              <input
                type="checkbox"
                checked={settings.anonymousPreviewEnabled}
                onChange={(e) => setSettings({ ...settings, anonymousPreviewEnabled: e.target.checked })}
              />
              Anonymous preview enabled
            </label>
            <label>
              Max upload bytes
              <input
                type="number"
                value={settings.maxUploadBytes}
                onChange={(e) => setSettings({ ...settings, maxUploadBytes: Number(e.target.value) })}
              />
            </label>
            <p className="muted">Retention: {settings.retentionNote}</p>
          </div>

          <h2 id="ai-provider">Optional AI enrichment</h2>
          <p className="muted">
            Disabled by default. Core reader works without AI. Derived enrichments only — source ClearDoc is never
            silently mutated. Saving an external connection (provider + API key) auto-aligns policy: provider =
            external, allow-external on, local-only off, AI enabled — so enrichment is not a dead end.
          </p>
          <div className="admin-grid">
            <label className="check">
              <input
                type="checkbox"
                checked={settings.aiEnabled}
                onChange={(e) => setSettings({ ...settings, aiEnabled: e.target.checked })}
              />
              AI enrichment enabled
            </label>
            <label className="check">
              <input
                type="checkbox"
                checked={settings.aiLocalOnly}
                onChange={(e) => setSettings({ ...settings, aiLocalOnly: e.target.checked })}
              />
              Local-only processing
            </label>
            <label className="check">
              <input
                type="checkbox"
                checked={settings.aiAllowExternal}
                onChange={(e) => setSettings({ ...settings, aiAllowExternal: e.target.checked })}
              />
              Allow sending content externally
            </label>
            <label>
              Provider
              <select
                value={settings.aiProviderId}
                onChange={(e) => setSettings({ ...settings, aiProviderId: e.target.value })}
              >
                <option value="mock-local">mock-local (deterministic demo)</option>
                <option value="ollama">ollama (local)</option>
                <option value="external">external (OpenAI-compatible HTTP)</option>
              </select>
            </label>
            <label>
              Daily quota
              <input
                type="number"
                value={settings.aiDailyQuota}
                onChange={(e) => setSettings({ ...settings, aiDailyQuota: Number(e.target.value) })}
              />
            </label>
            <label>
              Max retries
              <input
                type="number"
                value={settings.aiMaxRetries}
                onChange={(e) => setSettings({ ...settings, aiMaxRetries: Number(e.target.value) })}
              />
            </label>
          </div>
          <button type="button" onClick={() => void saveSettings()}>
            Save policy
          </button>

          {aiConnection && (
            <div className="ai-connection-block" data-testid="ai-provider-connection">
              <h3>AI provider connection</h3>
              <p className="muted">
                Pick a known external provider (or Custom), choose a model, and paste an API key. Ollama stays local.
                Keys are stored server-side and never echoed in full. Env vars remain a bootstrap fallback (
                <code>CLEARREAD_AI_*</code>
                ). Compose default Ollama URL: <code>http://host.docker.internal:11434</code>.
              </p>
              <div className="status-bar">
                <span className={`status-dot${aiConnection.ollamaAvailable ? "" : " err"}`} />
                <span>
                  Ollama: {aiConnection.ollamaAvailable ? "connected (configured)" : "not configured"} · source{" "}
                  {aiConnection.ollamaSource}
                </span>
                <span className={`status-dot${aiConnection.externalAvailable ? "" : " err"}`} />
                <span>
                  External: {aiConnection.externalAvailable ? "connected (configured)" : "not configured"}
                  {aiConnection.apiKeyConfigured ? ` · key ${aiConnection.apiKeyHint}` : " · no API key"}
                </span>
              </div>

              <h4 className="ai-connection-subhead">Ollama (local)</h4>
              <div className="admin-grid">
                <label className="check">
                  <input
                    type="checkbox"
                    checked={aiConnection.ollamaEnabled}
                    onChange={(e) => setAiConnection({ ...aiConnection, ollamaEnabled: e.target.checked })}
                    disabled={role !== "admin"}
                  />
                  Enable Ollama
                </label>
                <label>
                  Ollama base URL
                  <input
                    type="url"
                    value={aiConnection.ollamaBaseUrl}
                    placeholder={aiConnection.defaults?.ollamaBaseUrl || "http://host.docker.internal:11434"}
                    onChange={(e) => setAiConnection({ ...aiConnection, ollamaBaseUrl: e.target.value })}
                    disabled={role !== "admin"}
                  />
                </label>
                <label>
                  Ollama model
                  <input
                    type="text"
                    value={aiConnection.ollamaModel}
                    placeholder={aiConnection.defaults?.ollamaModel || "llama3.2"}
                    onChange={(e) => setAiConnection({ ...aiConnection, ollamaModel: e.target.value })}
                    disabled={role !== "admin"}
                  />
                </label>
              </div>

              <h4 className="ai-connection-subhead">External provider</h4>
              <div className="admin-grid ai-external-grid" data-testid="ai-external-provider">
                <label className="check">
                  <input
                    type="checkbox"
                    checked={aiConnection.externalEnabled}
                    onChange={(e) => {
                      const on = e.target.checked;
                      setAiConnection({ ...aiConnection, externalEnabled: on });
                      if (on) guidePolicyForExternal();
                    }}
                    disabled={role !== "admin"}
                  />
                  Enable external provider
                </label>
                <label>
                  Provider
                  <select
                    data-testid="external-provider-select"
                    value={externalPresetId}
                    onChange={(e) => selectExternalPreset(e.target.value as ExternalProviderPresetId)}
                    disabled={role !== "admin"}
                  >
                    {EXTERNAL_PROVIDER_PRESETS.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.label}
                      </option>
                    ))}
                  </select>
                  <span className="field-hint muted">{externalPreset.description}</span>
                </label>
                <label>
                  Model
                  {!customModelDraft && externalPreset.models.length > 0 ? (
                    <select
                      data-testid="external-model-select"
                      value={
                        externalPreset.models.includes(aiConnection.externalModel)
                          ? aiConnection.externalModel
                          : externalPreset.defaultModel
                      }
                      onChange={(e) => {
                        if (e.target.value === "__custom__") {
                          setCustomModelDraft(true);
                          return;
                        }
                        setAiConnection({ ...aiConnection, externalModel: e.target.value });
                      }}
                      disabled={role !== "admin"}
                    >
                      {externalPreset.models.map((m) => (
                        <option key={m} value={m}>
                          {m}
                        </option>
                      ))}
                      <option value="__custom__">Custom model id…</option>
                    </select>
                  ) : (
                    <input
                      type="text"
                      data-testid="external-model-custom"
                      value={aiConnection.externalModel}
                      placeholder={externalPreset.defaultModel}
                      onChange={(e) => setAiConnection({ ...aiConnection, externalModel: e.target.value })}
                      disabled={role !== "admin"}
                    />
                  )}
                  {customModelDraft && role === "admin" ? (
                    <button
                      type="button"
                      className="linkish"
                      onClick={() => {
                        setCustomModelDraft(false);
                        setAiConnection({
                          ...aiConnection,
                          externalModel: externalPreset.defaultModel,
                        });
                      }}
                    >
                      Back to model list
                    </button>
                  ) : null}
                </label>
                <label>
                  API key {aiConnection.apiKeyConfigured ? `(saved ${aiConnection.apiKeyHint})` : "(not set)"}
                  <input
                    type="password"
                    autoComplete="off"
                    data-testid="external-api-key"
                    value={apiKeyDraft}
                    placeholder={aiConnection.apiKeyConfigured ? "Leave blank to keep existing key" : "Paste API key"}
                    onChange={(e) => setApiKeyDraft(e.target.value)}
                    disabled={role !== "admin" || clearApiKey}
                  />
                </label>
                <label className="check">
                  <input
                    type="checkbox"
                    checked={clearApiKey}
                    onChange={(e) => {
                      setClearApiKey(e.target.checked);
                      if (e.target.checked) setApiKeyDraft("");
                    }}
                    disabled={role !== "admin"}
                  />
                  Clear saved API key
                </label>
              </div>

              <details
                className="ai-advanced-details"
                open={showExternalAdvanced}
                onToggle={(e) => setShowExternalAdvanced((e.target as HTMLDetailsElement).open)}
              >
                <summary>Advanced URL override</summary>
                <p className="muted">
                  Presets fill a sensible endpoint. Override only for Azure deployments or custom gateways.
                </p>
                <label>
                  External base URL
                  <input
                    type="url"
                    data-testid="external-base-url"
                    value={aiConnection.externalBaseUrl}
                    placeholder={
                      externalPreset.baseUrlPlaceholder ||
                      aiConnection.defaults?.externalBaseUrl ||
                      "https://api.example.com/v1/chat/completions"
                    }
                    onChange={(e) => {
                      const url = e.target.value;
                      setAiConnection({ ...aiConnection, externalBaseUrl: url });
                      setExternalPresetId(matchPresetFromBaseUrl(url));
                    }}
                    disabled={role !== "admin"}
                  />
                </label>
              </details>

              {role === "admin" && (
                <div className="ai-connection-actions">
                  <button type="button" onClick={() => void saveAiConnection()}>
                    Save AI connection
                  </button>
                  <button
                    type="button"
                    className="secondary"
                    data-testid="ai-test-connection"
                    disabled={testBusy}
                    onClick={() => void testAiConnection(aiConnection.externalEnabled ? "external" : "auto")}
                  >
                    {testBusy ? "Testing…" : "Test connection"}
                  </button>
                </div>
              )}
              {testResult && (
                <p
                  className={testResult.ok ? "status-bar" : "alert"}
                  role="status"
                  data-testid="ai-test-connection-result"
                >
                  <span className={`status-dot${testResult.ok ? "" : " err"}`} />
                  {testResult.message}
                </p>
              )}
              {settings &&
                aiConnection.externalEnabled &&
                (settings.aiProviderId !== "external" ||
                  settings.aiLocalOnly ||
                  !settings.aiAllowExternal) && (
                  <p className="alert" role="status" data-testid="ai-policy-mismatch">
                    Policy still blocks external enrichment (provider={settings.aiProviderId}, local-only=
                    {String(settings.aiLocalOnly)}, allow-external={String(settings.aiAllowExternal)}). Save the
                    AI connection to auto-align, or fix the policy checkboxes above and Save policy.
                  </p>
                )}
            </div>
          )}
        </>
      )}

      <h2>Processing jobs</h2>
      {jobs.length === 0 ? (
        <p className="muted">No jobs visible (admin role required for inspection).</p>
      ) : (
        <ul className="library-list">
          {jobs.map((j) => (
            <li key={j.id}>
              <code>{j.type}</code> · {j.status}
              {j.documentId ? ` · doc ${j.documentId.slice(0, 8)}…` : ""}
              {j.lastError ? <div className="muted">{j.lastError}</div> : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
