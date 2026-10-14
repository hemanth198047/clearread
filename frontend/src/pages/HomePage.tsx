import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { apiFetch, formatApiError } from "../api/client";
import { fetchRecentDocuments, type RecentDocMeta } from "../api/recentDocuments";
import { useAuth } from "../auth/AuthProvider";
import { DEMO_ACCOUNTS, useBranding } from "../branding/BrandingProvider";
import { LocalFileNav } from "../library/LocalFileNav";
import { useSharedLocalLibrary } from "../library/LocalLibraryProvider";
import { fetchCapabilitiesCached } from "../library/useLocalLibrary";

type DocMeta = RecentDocMeta;

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
  const [docsError, setDocsError] = useState<string | null>(null);
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null);
  const [apiReachable, setApiReachable] = useState<boolean | null>(null);

  const token = auth.getAccessToken();
  const signedIn = Boolean(auth.user || (auth.devBypass && token));
  const localLib = useSharedLocalLibrary();
  const showLocalRail = localLib.enabled && (localLib.folders.length > 0 || localLib.scanning || Boolean(localLib.lastScanAt));

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
    void fetchCapabilitiesCached()
      .then((result) => {
        if (result.source === "api" && result.data) {
          setCapabilities(result.data as Capabilities);
          setApiReachable(true);
          const first = result.data.formats?.find((f) => f.ingest === "supported");
          if (first?.formatId) setFormatId((prev) => prev || first.formatId);
        } else {
          setCapabilities(null);
          setApiReachable(false);
        }
      })
      .catch((e) => {
        setCapabilities(null);
        setApiReachable(false);
        setError(formatApiError(e, "Capabilities"));
      });
  }, []);

  useEffect(() => {
    if (!signedIn || !token) return;
    let cancelled = false;
    void fetchRecentDocuments(token).then((result) => {
      if (cancelled) return;
      setDocs(result.docs);
      setDocsError(result.error);
    });
    return () => {
      cancelled = true;
    };
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
      return `${raw} — Open an existing document from Recent, or remove documents to free quota.`;
    }
    if (/Cannot reach the ClearRead API/i.test(raw)) return raw;
    return formatApiError(err);
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

  const homeBody = (
    <>
      <section className="hero">
        <p className="page-eyebrow">Self-hosted reading workspace</p>
        <h1>
          {branding.productName}
          <span>.</span>
        </h1>
        <p>
          Turn text-based files into a calm, structured reading experience — without rewriting your originals or
          requiring AI.
        </p>
        <div className="hero-meta" aria-label="Product status">
          <span className="chip">Preserve originals</span>
          <span className="chip">No AI required</span>
          <span className="chip">
            API {apiReachable === null ? "…" : apiReachable ? "reachable" : "unreachable"}
          </span>
          {capabilities?.registryVersion ? (
            <span className="chip">Registry {capabilities.registryVersion}</span>
          ) : null}
        </div>
      </section>

      <dl className="page-meta-strip" aria-label="Workspace snapshot">
        <div>
          <dt>Session</dt>
          <dd>{auth.loading ? "…" : signedIn ? "Signed in" : "Guest"}</dd>
        </div>
        <div>
          <dt>Recent</dt>
          <dd>{signedIn ? docs.length : "—"}</dd>
        </div>
        <div>
          <dt>API</dt>
          <dd>{apiReachable === null ? "…" : apiReachable ? "Up" : "Down"}</dd>
        </div>
        <div>
          <dt>Reader</dt>
          <dd>Day · Sepia · Night</dd>
        </div>
      </dl>

      <div className="status-bar" role="status">
        <span className={`status-dot${apiReachable === false ? " err" : apiReachable ? "" : " busy"}`} />
        <span>
          {auth.loading
            ? "Checking session…"
            : signedIn
              ? `Signed in · ${docs.length} recent document${docs.length === 1 ? "" : "s"}`
              : "Guest · sign in to ingest and reopen documents"}
        </span>
        <span className="muted">Theme chrome from Rebeautifier · reader modes unchanged</span>
      </div>

      <div className="workspace-grid">
        <section className="panel">
          {!signedIn ? (
            <div className="access-panel auth-entry" data-testid="access-panel">
              <div className="page-header" style={{ border: 0, padding: 0, margin: 0 }}>
                <div>
                  <p className="page-eyebrow">Welcome</p>
                  <h2 className="auth-entry-title">
                    Sign in to ClearRead<span className="brand-dot">.</span>
                  </h2>
                  <p className="lede">
                    Continue with your workspace account, or create one on the local IdP. After signup you join the
                    default Demo Workspace automatically.
                  </p>
                </div>
              </div>
              <div className="auth-entry-grid">
                <div className="auth-entry-card">
                  <h3>Open your workspace</h3>
                  <p className="muted">OIDC sign-in via Keycloak — session stays on this install.</p>
                  <div className="access-actions">
                    <button type="button" onClick={() => void auth.login()}>
                      Sign in
                    </button>
                    <button
                      type="button"
                      className="secondary"
                      onClick={() => void auth.register()}
                      data-testid="create-account"
                    >
                      Create account
                    </button>
                    <Link className="button secondary" to="/library">
                      Browse library
                    </Link>
                  </div>
                </div>
                <aside className="auth-entry-aside" aria-label="What you get">
                  <ul className="auth-entry-bullets">
                    <li>Ingest paste, upload, and URL documents</li>
                    <li>Library, search, and local folder rails</li>
                    <li>Day / Sepia / Night reader with Structure modes</li>
                  </ul>
                </aside>
              </div>
              {branding.showDemoAccounts ? (
                <div className="demo-accounts" data-testid="demo-accounts">
                  <h3>Demo accounts</h3>
                  <p className="muted">
                    Local Compose stack only (hidden when <code>CLEARREAD_SHOW_DEMO_ACCOUNTS=false</code>). Keycloak
                    console admin is separate: <code>admin</code> / <code>admin</code> on the master realm.
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
              <div className="page-header" style={{ border: 0, padding: 0, margin: 0 }}>
                <div>
                  <h2>Ingest</h2>
                  <p className="lede">Paste, upload, or fetch a text file. Close the reader anytime — reopen from Recent or Library.</p>
                </div>
                <div className="page-header-actions">
                  <Link className="button secondary" to="/library">
                    Library
                  </Link>
                </div>
              </div>

              <div className="tabs" role="tablist" aria-label="Ingest method">
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
                <form className="form-stack" onSubmit={onPaste}>
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
                  <p>
                    <button type="submit" disabled={busy || !content.trim()}>
                      {busy ? "Processing…" : "Read document"}
                    </button>
                  </p>
                </form>
              ) : null}

              {tab === "upload" ? (
                <form className="form-stack" onSubmit={onUpload}>
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
                  <label className="check" style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}>
                    <input
                      type="checkbox"
                      checked={forceNewUpload}
                      onChange={(e) => setForceNewUpload(e.target.checked)}
                    />
                    Upload as a new document even if identical content already exists
                  </label>
                  <p>
                    <button type="submit" disabled={busy || !file}>
                      {busy ? "Processing…" : "Upload & read"}
                    </button>
                  </p>
                </form>
              ) : null}

              {tab === "url" ? (
                <form className="form-stack" onSubmit={onUrl}>
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
                  <p className="muted">
                    SSRF-hardened fetch: private/metadata addresses blocked. Ordinary web pages are not scraped — use a
                    raw file URL, or paste/upload instead.
                  </p>
                  <p>
                    <button type="submit" disabled={busy || !url.trim()}>
                      {busy ? "Fetching…" : "Fetch & read"}
                    </button>
                  </p>
                </form>
              ) : null}

              {progress && (
                <div className="status-bar">
                  <span className="status-dot busy" />
                  <span className="status-line">{progress}</span>
                </div>
              )}
              {error && (
                <p className="alert" role="alert">
                  {error}
                </p>
              )}
            </>
          )}

          <details className="capability-details" id="capabilities">
            <summary>
              Format support{capabilities?.registryVersion ? ` (registry ${capabilities.registryVersion})` : ""}
            </summary>
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
        </section>

        <aside className="side-rail" aria-label="Workspace shortcuts">
          <div className="rail-card">
            <h2>Recent in workspace</h2>
            {docsError && (
              <p className="alert" role="alert">
                {docsError}
              </p>
            )}
            {!signedIn && <p className="muted">Sign in to see recent documents and reopen them after close.</p>}
            {signedIn && !docsError && docs.length === 0 && (
              <p className="muted">No documents yet. Paste or upload to start reading.</p>
            )}
            {docs.length > 0 && (
              <ul className="recent-list">
                {docs.map((d) => (
                  <li key={d.id}>
                    <div className="library-item-main">
                      <Link to={`/documents/${d.id}`}>{d.title || "Untitled"}</Link>
                      <div className="library-meta muted">
                        <span className="format-chip">{d.formatId}</span>
                        <span>{d.status}</span>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div className="rail-card">
            <h2>Reader modes</h2>
            <p className="muted" style={{ margin: 0 }}>
              Inside a document: Day, Sepia, and Night stay switchable. Structure: Conservative / Balanced / Detailed —
              presentation only; originals are never mutated.
            </p>
          </div>
          <div className="rail-card">
            <h2>Quick links</h2>
            <div className="access-actions">
              <Link className="button secondary" to="/library">
                Library
              </Link>
              <Link className="button secondary" to="/admin">
                Admin
              </Link>
            </div>
          </div>
        </aside>
      </div>
    </>
  );

  if (!showLocalRail) {
    return homeBody;
  }

  return (
    <div className="library-layout home-with-local">
      <LocalFileNav
        folders={localLib.folders}
        files={localLib.files}
        enabled={localLib.enabled}
        scanning={localLib.scanning}
        lastScanAt={localLib.lastScanAt}
        error={localLib.error}
        formatsSource={localLib.formatsSource}
        title="Local files"
        onRescan={() => void localLib.rescan()}
      />
      <div className="home-main">{homeBody}</div>
    </div>
  );
}
