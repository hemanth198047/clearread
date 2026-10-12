import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiFetch } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { DEMO_ACCOUNTS, useBranding } from "../branding/BrandingProvider";

type DocMeta = {
  id: string;
  title: string;
  status: string;
  formatId: string;
  jobId?: string;
  duplicateOfExisting?: boolean;
  message?: string;
};

type CapabilityFormat = {
  formatId: string;
  displayName?: string;
  extensions?: string[];
  mimeTypes?: string[];
  ingest?: string;
  notes?: string;
  features?: Record<string, string>;
};

type Capabilities = {
  registryVersion?: string;
  formats?: CapabilityFormat[];
  exports?: { note?: string; converters?: { targetFormat: string; converterId: string }[] };
};

export function HomePage() {
  const branding = useBranding();
  const auth = useAuth();
  const navigate = useNavigate();
  const [tab, setTab] = useState<"paste" | "upload" | "url">("paste");
  const [title, setTitle] = useState("");
  const [formatId, setFormatId] = useState("markdown");
  const [content, setContent] = useState("# Welcome\n\nPaste Markdown, JSON, YAML, CSV, or other text formats.\n");
  const [file, setFile] = useState<File | null>(null);
  const [forceNewUpload, setForceNewUpload] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [docs, setDocs] = useState<DocMeta[]>([]);
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null);

  const token = auth.getAccessToken();
  const signedIn = Boolean(auth.user || (auth.devBypass && token));

  const supportedFormats = useMemo(
    () => (capabilities?.formats ?? []).filter((f) => f.ingest === "supported"),
    [capabilities]
  );
  const unsupportedFormats = useMemo(
    () => (capabilities?.formats ?? []).filter((f) => f.ingest && f.ingest !== "supported"),
    [capabilities]
  );
  const acceptAttr = useMemo(() => {
    const parts: string[] = [];
    for (const f of supportedFormats) {
      for (const ext of f.extensions ?? []) parts.push(ext);
      for (const mime of f.mimeTypes ?? []) parts.push(mime);
    }
    return parts.length ? parts.join(",") : undefined;
  }, [supportedFormats]);

  useEffect(() => {
    apiFetch("/v1/capabilities", null)
      .then((data) => {
        const caps = data as Capabilities;
        setCapabilities(caps);
        const first = caps.formats?.find((f) => f.ingest === "supported");
        if (first?.formatId) setFormatId((prev) => prev || first.formatId);
      })
      .catch(() => setCapabilities(null));
  }, []);

  useEffect(() => {
    if (!signedIn || !token) return;
    apiFetch("/v1/documents", token)
      .then((data) => setDocs(data as DocMeta[]))
      .catch(() => setDocs([]));
  }, [signedIn, token]);

  async function waitUntilReady(id: string, accessToken: string) {
    for (let i = 0; i < 80; i++) {
      const meta = (await apiFetch(`/v1/documents/${id}`, accessToken)) as DocMeta;
      setProgress(`Status: ${meta.status}`);
      if (meta.status === "ready") return meta;
      if (meta.status === "failed" || meta.status === "cancelled") {
        throw new Error(`Processing ${meta.status}`);
      }
      await new Promise((r) => setTimeout(r, 500));
    }
    throw new Error("Timed out waiting for document");
  }

  function formatError(err: unknown): string {
    const raw = err instanceof Error ? err.message : String(err);
    if (/unsupported format/i.test(raw) || /UNSUPPORTED_MEDIA_TYPE/i.test(raw) || /415/.test(raw)) {
      return `${raw} — See Format support below or GET /v1/capabilities.`;
    }
    if (/quota exceeded/i.test(raw) || /PAYLOAD_TOO_LARGE/i.test(raw)) {
      return `${raw} — Open an existing document from Recent below, or remove documents to free quota.`;
    }
    return raw;
  }

  function resetFileInput() {
    setFile(null);
    setForceNewUpload(false);
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  async function openIngested(created: DocMeta, accessToken: string) {
    if (created.duplicateOfExisting) {
      setProgress(created.message ?? "Identical content already in your library — opening existing…");
    }
    if (created.status !== "ready") {
      await waitUntilReady(created.id, accessToken);
    }
    navigate(`/documents/${created.id}`);
  }

  async function onPaste(e: FormEvent) {
    e.preventDefault();
    if (!token) return;
    setBusy(true);
    setError(null);
    setProgress("Uploading…");
    try {
      const created = (await apiFetch("/v1/documents/paste", token, {
        method: "POST",
        body: JSON.stringify({ title, formatId, content }),
      })) as DocMeta;
      await openIngested(created, token);
    } catch (err) {
      setError(formatError(err));
    } finally {
      setBusy(false);
      setProgress(null);
    }
  }

  async function onUpload(e: FormEvent) {
    e.preventDefault();
    if (!token || !file) return;
    setBusy(true);
    setError(null);
    setProgress("Uploading…");
    try {
      const form = new FormData();
      form.append("file", file);
      if (title) form.append("title", title);
      if (forceNewUpload) form.append("forceNew", "true");
      const created = (await apiFetch("/v1/documents/upload", token, {
        method: "POST",
        body: form,
      })) as DocMeta;
      resetFileInput();
      await openIngested(created, token);
    } catch (err) {
      setError(formatError(err));
    } finally {
      setBusy(false);
      setProgress(null);
    }
  }

  async function onUrl(e: FormEvent) {
    e.preventDefault();
    if (!token || !url.trim()) return;
    setBusy(true);
    setError(null);
    setProgress("Validating URL & enqueueing fetch…");
    try {
      const created = (await apiFetch("/v1/documents/from-url", token, {
        method: "POST",
        body: JSON.stringify({ title, url: url.trim() }),
      })) as DocMeta;
      setProgress(`Fetch job ${created.jobId ?? ""} — waiting…`);
      await waitUntilReady(created.id, token);
      navigate(`/documents/${created.id}`);
    } catch (err) {
      setError(formatError(err));
    } finally {
      setBusy(false);
      setProgress(null);
    }
  }

  return (
    <>
      <section className="hero">
        <h1>{branding.productName}</h1>
        <p>
          Turn text-based files into a calm, structured reading experience — without rewriting your originals or
          requiring AI.
        </p>
      </section>

      <section className="panel">
        {!signedIn ? (
          <div className="access-panel" data-testid="access-panel">
            <h2>Get access</h2>
            <p>
              <strong>Sign in</strong> with an existing account, or <strong>Create account</strong> to register via the
              local IdP (Keycloak self-registration). After signup you join the default Demo Workspace automatically.
            </p>
            <div className="access-actions">
              <button type="button" onClick={() => void auth.login()}>
                Sign in
              </button>
              <button type="button" className="secondary" onClick={() => void auth.register()} data-testid="create-account">
                Create account
              </button>
            </div>
            {branding.showDemoAccounts ? (
              <div className="demo-accounts" data-testid="demo-accounts">
                <h3>Demo accounts</h3>
                <p className="muted">
                  Local Compose stack only (hidden when <code>CLEARREAD_SHOW_DEMO_ACCOUNTS=false</code>). Keycloak console
                  admin is separate: <code>admin</code> / <code>admin</code> on the master realm.
                </p>
                <ul>
                  {DEMO_ACCOUNTS.map((a) => (
                    <li key={a.username}>
                      <code>{a.username}</code> / <code>{a.password}</code>
                      <span className="muted"> — {a.role}</span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : (
              <p className="muted">Production access is via your organization IdP (OIDC SSO / invite).</p>
            )}
          </div>
        ) : (
          <>
            <div className="tabs">
              <button type="button" className={tab === "paste" ? "" : "secondary"} onClick={() => setTab("paste")}>
                Paste
              </button>
              <button type="button" className={tab === "upload" ? "" : "secondary"} onClick={() => setTab("upload")}>
                Upload
              </button>
              <button type="button" className={tab === "url" ? "" : "secondary"} onClick={() => setTab("url")}>
                From URL
              </button>
            </div>

            {tab === "paste" ? (
              <form onSubmit={onPaste}>
                <label>
                  Title
                  <input type="text" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Optional title" />
                </label>
                <label>
                  Format
                  <select value={formatId} onChange={(e) => setFormatId(e.target.value)}>
                    {(supportedFormats.length
                      ? supportedFormats
                      : [{ formatId: "markdown", displayName: "Markdown" }]
                    ).map((f) => (
                      <option key={f.formatId} value={f.formatId}>
                        {f.displayName ?? f.formatId}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Content
                  <textarea value={content} onChange={(e) => setContent(e.target.value)} />
                </label>
                <p style={{ marginTop: "1rem" }}>
                  <button type="submit" disabled={busy || !content.trim()}>
                    {busy ? "Processing…" : "Read document"}
                  </button>
                </p>
              </form>
            ) : null}

            {tab === "upload" ? (
              <form onSubmit={onUpload}>
                <label>
                  Title
                  <input type="text" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Optional title" />
                </label>
                <label>
                  File (text-based formats, max 10 MiB)
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept={acceptAttr}
                    onChange={(e) => {
                      setFile(e.target.files?.[0] ?? null);
                      setForceNewUpload(false);
                    }}
                  />
                </label>
                <label className="check" style={{ marginTop: "0.75rem", display: "flex", gap: "0.5rem", alignItems: "center" }}>
                  <input
                    type="checkbox"
                    checked={forceNewUpload}
                    onChange={(e) => setForceNewUpload(e.target.checked)}
                  />
                  Upload as a new document even if identical content already exists
                </label>
                <p style={{ marginTop: "1rem" }}>
                  <button type="submit" disabled={busy || !file}>
                    {busy ? "Processing…" : "Upload & read"}
                  </button>
                </p>
              </form>
            ) : null}

            {tab === "url" ? (
              <form onSubmit={onUrl}>
                <label>
                  Title
                  <input type="text" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Optional title" />
                </label>
                <label>
                  Direct file URL (http/https)
                  <input
                    type="url"
                    value={url}
                    onChange={(e) => setUrl(e.target.value)}
                    placeholder="https://example.com/path/to/file.json"
                    required
                  />
                </label>
                <p className="muted" style={{ marginTop: "0.5rem" }}>
                  SSRF-hardened fetch: private/metadata addresses blocked. Ordinary web pages are not scraped — use a
                  raw file URL, or paste/upload instead.
                </p>
                <p style={{ marginTop: "1rem" }}>
                  <button type="submit" disabled={busy || !url.trim()}>
                    {busy ? "Fetching…" : "Fetch & read"}
                  </button>
                </p>
              </form>
            ) : null}

            {progress && <p className="status-line muted">{progress}</p>}
            {error && (
              <p className="status-line" role="alert">
                {error}
              </p>
            )}
          </>
        )}

        <details className="capability-details" id="capabilities">
          <summary>Format support{capabilities?.registryVersion ? ` (registry ${capabilities.registryVersion})` : ""}</summary>
          <p className="muted">
            Claims follow <code>GET /v1/capabilities</code>. Unsupported means unsupported — no silent fake support.
          </p>
          <ul className="capability-list">
            {supportedFormats.map((f) => (
              <li key={f.formatId}>
                <strong>{f.displayName ?? f.formatId}</strong>
                <span className="muted"> · ingestible</span>
                {f.notes ? <div className="muted">{f.notes}</div> : null}
              </li>
            ))}
            {unsupportedFormats.map((f) => (
              <li key={f.formatId}>
                <strong>{f.displayName ?? f.formatId}</strong>
                <span className="muted"> · {f.ingest}</span>
                {f.notes ? <div className="muted">{f.notes}</div> : null}
              </li>
            ))}
          </ul>
          {capabilities?.exports?.note ? <p className="muted">{capabilities.exports.note}</p> : null}
        </details>

        {docs.length > 0 && (
          <div>
            <h2>Recent in workspace</h2>
            <ul>
              {docs.map((d) => (
                <li key={d.id}>
                  <a href={`/documents/${d.id}`}>{d.title}</a>{" "}
                  <span className="muted">
                    ({d.formatId}, {d.status})
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>
    </>
  );
}
