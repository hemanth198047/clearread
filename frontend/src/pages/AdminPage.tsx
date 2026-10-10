import { useEffect, useState } from "react";
import { apiFetch } from "../api/client";
import { useAuth } from "../auth/AuthProvider";

type Settings = {
  sharingEnabled: boolean;
  anonymousPreviewEnabled: boolean;
  retentionDays: number | null;
  maxUploadBytes: number;
  retentionNote: string;
};

type Job = { id: string; type: string; status: string; documentId: string; lastError: string };

export function AdminPage() {
  const auth = useAuth();
  const token = auth.getAccessToken();
  const [tenantId, setTenantId] = useState("");
  const [settings, setSettings] = useState<Settings | null>(null);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    (async () => {
      try {
        const me = (await apiFetch("/v1/me", token)) as { activeTenantId: string; activeRole: string };
        setTenantId(me.activeTenantId);
        const s = (await apiFetch(`/v1/tenants/${me.activeTenantId}/settings`, token)) as Settings;
        setSettings(s);
        if (me.activeRole === "admin") {
          const j = (await apiFetch(`/v1/tenants/${me.activeTenantId}/jobs`, token)) as Job[];
          setJobs(j);
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    })();
  }, [token]);

  async function saveSettings() {
    if (!token || !tenantId || !settings) return;
    setMsg(null);
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
      <p className="muted">Sharing policy, retention (default indefinite), upload limit, and job inspection.</p>
      {error && <p role="alert">{error}</p>}
      {msg && <p className="status-line">{msg}</p>}
      {settings && (
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
          <button type="button" onClick={() => void saveSettings()}>
            Save settings
          </button>
        </div>
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
