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
};

export function HomePage() {
  const branding = useBranding();
  const auth = useAuth();
  const navigate = useNavigate();
  const [tab, setTab] = useState<"paste" | "upload">("paste");
  const [title, setTitle] = useState("");
  const [formatId, setFormatId] = useState("markdown");
  const [content, setContent] = useState("# Welcome\n\nPaste Markdown or plain text to read beautifully.\n");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
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
    for (let i = 0; i < 40; i++) {
      const meta = (await apiFetch(`/v1/documents/${id}`, accessToken)) as DocMeta;
      if (meta.status === "ready") return meta;
      if (meta.status === "failed") throw new Error("Processing failed");
      await new Promise((r) => setTimeout(r, 500));
    }
    throw new Error("Timed out waiting for document");
  }

  async function onPaste(e: FormEvent) {
    e.preventDefault();
    if (!token) return;
    setBusy(true);
    setError(null);
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
    }
  }

  async function onUpload(e: FormEvent) {
    e.preventDefault();
    if (!token || !file) return;
    setBusy(true);
    setError(null);
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
    }
  }

  return (
    <>
      <section className="hero">
        <h1>{branding.productName}</h1>
        <p>
          Turn Markdown and plain text into a calm, structured reading experience — without rewriting your originals
          or requiring AI.
        </p>
      </section>

      <section className="panel">
        {!signedIn ? (
          <p className="muted">Sign in to paste or upload a document. Local demo user: <code>demo</code> / <code>demo</code>.</p>
        ) : (
          <>
            <div className="tabs">
              <button type="button" className={tab === "paste" ? "" : "secondary"} onClick={() => setTab("paste")}>
                Paste
              </button>
              <button type="button" className={tab === "upload" ? "" : "secondary"} onClick={() => setTab("upload")}>
                Upload
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
            ) : (
              <form onSubmit={onUpload}>
                <label>
                  Title
                  <input type="text" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Optional title" />
                </label>
                <label>
                  File (.md or .txt, max 10 MiB)
                  <input
                    type="file"
                    accept=".md,.markdown,.txt,text/plain,text/markdown"
                    onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                  />
                </label>
                <p style={{ marginTop: "1rem" }}>
                  <button type="submit" disabled={busy || !file}>
                    {busy ? "Processing…" : "Upload & read"}
                  </button>
                </p>
              </form>
            )}
            {error && <p className="status-line" role="alert">{error}</p>}
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
