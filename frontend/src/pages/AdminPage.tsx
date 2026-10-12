import { FormEvent, useEffect, useState } from "react";
import { apiFetch } from "../api/client";
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
  const [jobs, setJobs] = useState<Job[]>([]);
  const [invites, setInvites] = useState<Invite[]>([]);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState("author");
  const [lastInviteUrl, setLastInviteUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    (async () => {
      try {
        const me = (await apiFetch("/v1/me", token)) as { activeTenantId: string; activeRole: string };
        setTenantId(me.activeTenantId);
        setRole(me.activeRole);
        const s = (await apiFetch(`/v1/tenants/${me.activeTenantId}/settings`, token)) as Settings;
        setSettings(s);
        if (me.activeRole === "admin") {
          const j = (await apiFetch(`/v1/tenants/${me.activeTenantId}/jobs`, token)) as Job[];
          setJobs(j);
          const inv = (await apiFetch(`/v1/tenants/${me.activeTenantId}/invites`, token)) as Invite[];
          setInvites(inv);
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
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
      setError(err instanceof Error ? err.message : String(err));
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
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  if (!auth.user && !auth.devBypass) {
    return <p className="muted">Sign in as a workspace admin to manage settings.</p>;
  }

  return (
    <section className="panel">
      <h1>Workspace admin</h1>
      <p className="muted">
        Sharing policy, retention, upload limit, optional AI enrichment, invites, and job inspection.
        {role ? ` Active role: ${role}.` : ""}
      </p>
      {error && <p role="alert">{error}</p>}
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

          <h2>Optional AI enrichment</h2>
          <p className="muted">
            Disabled by default. Core reader works without AI. External providers require explicit allow-external and
            local-only off.
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
                <option value="external">external (requires allow-external)</option>
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
            Save settings
          </button>
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
