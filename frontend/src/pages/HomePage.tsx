import { FormEvent, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiFetch } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { useBranding } from "../branding/BrandingProvider";

type DocMeta = {
  id: string;
  title: string;
  status: string;
  formatId: string;
  jobId?: string;
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
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [docs, setDocs] = useState<DocMeta[]>([]);

  const token = auth.getAccessToken();
  const signedIn = Boolean(auth.user || (auth.devBypass && token));

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
      await waitUntilReady(created.id, token);
      navigate(`/documents/${created.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
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
      const created = (await apiFetch("/v1/documents/upload", token, {
        method: "POST",
        body: form,
      })) as DocMeta;
      await waitUntilReady(created.id, token);
      navigate(`/documents/${created.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
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
      setError(err instanceof Error ? err.message : String(err));
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
          <p className="muted">
            Sign in to paste, upload, or fetch a document. Local demo user: <code>demo</code> / <code>demo</code>.
          </p>
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
                    <option value="markdown">Markdown</option>
                    <option value="plain-text">Plain text</option>
                    <option value="json">JSON</option>
                    <option value="yaml">YAML</option>
                    <option value="xml">XML</option>
                    <option value="csv">CSV</option>
                    <option value="tsv">TSV</option>
                    <option value="log">Log</option>
                    <option value="config">Config / env</option>
                    <option value="source-code">Source code</option>
                    <option value="html">HTML (sanitized)</option>
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
                    type="file"
                    accept=".md,.markdown,.txt,.json,.yaml,.yml,.xml,.csv,.tsv,.log,.ini,.conf,.properties,.env,.html,.htm,.java,.ts,.js,.py,.go,.rs,.rb,.kt,.cs,.c,.cpp,.sh,.sql,text/plain,text/markdown,application/json"
                    onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                  />
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
