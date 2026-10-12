import { FormEvent, useEffect, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { apiFetch } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import type { ClearDoc } from "../reader/ClearDocView";
import { EnrichmentReview } from "../reader/EnrichmentReview";
import { ReaderShell } from "../reader/ReaderShell";

type DocMeta = {
  id: string;
  title: string;
  status: string;
  originalSha256: string;
  formatId: string;
  currentRevisionId?: string;
};

type AcceptedEnrichment = {
  id: string;
  kind: string;
  status: string;
  providerId: string;
  modelId: string;
  promptVersion: string;
  generatedLabel: string;
  lastError: string;
  proposal: Record<string, unknown> | null;
  validation: { warnings?: string[]; errors?: string[] } | null;
};

type ShareLink = {
  id: string;
  documentId: string;
  requireAuth: boolean;
  allowDownload: boolean;
  allowExport: boolean;
  expiresAt: string;
  revokedAt: string;
  createdAt: string;
  label: string;
  token?: string;
};

type Diagnostics = {
  status: string;
  jobs?: { id: string; type: string; status: string; lastError: string; attempt: number; maxAttempts: number }[];
};

export function ReaderPage() {
  const { id } = useParams();
  const [searchParams] = useSearchParams();
  const initialFind = searchParams.get("q") ?? "";
  const auth = useAuth();
  const token = auth.getAccessToken();
  const [meta, setMeta] = useState<DocMeta | null>(null);
  const [doc, setDoc] = useState<ClearDoc | null>(null);
  const [originalText, setOriginalText] = useState<string | null>(null);
  const [originalError, setOriginalError] = useState<string | null>(null);
  const [originalLoading, setOriginalLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusNote, setStatusNote] = useState<string | null>(null);
  const [diagnostics, setDiagnostics] = useState<Diagnostics | null>(null);
  const [acceptedOverlay, setAcceptedOverlay] = useState<AcceptedEnrichment | null>(null);
  const [shareOpen, setShareOpen] = useState(false);
  const [shareLinks, setShareLinks] = useState<ShareLink[]>([]);
  const [shareMsg, setShareMsg] = useState<string | null>(null);
  const [expiresHours, setExpiresHours] = useState(72);
  const [allowDownload, setAllowDownload] = useState(false);
  const [allowExport, setAllowExport] = useState(false);
  const [requireAuth, setRequireAuth] = useState(true);
  const [maxViews, setMaxViews] = useState(50);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [exportFormats, setExportFormats] = useState<{ targetFormat: string; converterId: string }[]>([]);

  async function loadDiagnostics(docId: string, accessToken: string) {
    try {
      const d = (await apiFetch(`/v1/documents/${docId}/diagnostics`, accessToken)) as Diagnostics;
      setDiagnostics(d);
    } catch {
      setDiagnostics(null);
    }
  }

  async function loadReady(docId: string, accessToken: string) {
    const cleardoc = (await apiFetch(`/v1/documents/${docId}/cleardoc`, accessToken)) as ClearDoc;
    setDoc(cleardoc);
    setError(null);
    setStatusNote(null);
    setOriginalLoading(true);
    setOriginalError(null);
    try {
      const buf = (await apiFetch(`/v1/documents/${docId}/original`, accessToken)) as ArrayBuffer;
      setOriginalText(new TextDecoder("utf-8", { fatal: false }).decode(buf));
    } catch (err) {
      setOriginalText(null);
      setOriginalError(err instanceof Error ? err.message : String(err));
    } finally {
      setOriginalLoading(false);
    }
  }

  useEffect(() => {
    apiFetch("/v1/capabilities", null)
      .then((data) => {
        const converters = (data as { exports?: { converters?: { targetFormat: string; converterId: string }[] } })
          ?.exports?.converters;
        if (converters?.length) setExportFormats(converters);
      })
      .catch(() => setExportFormats([]));
  }, []);

  useEffect(() => {
    if (!id || !token) return;
    let cancelled = false;
    (async () => {
      try {
        let m = (await apiFetch(`/v1/documents/${id}`, token)) as DocMeta;
        if (cancelled) return;
        setMeta(m);
        for (let i = 0; i < 80 && m.status !== "ready" && m.status !== "failed" && m.status !== "cancelled"; i++) {
          setStatusNote(`Document status: ${m.status}…`);
          await new Promise((r) => setTimeout(r, 500));
          m = (await apiFetch(`/v1/documents/${id}`, token)) as DocMeta;
          if (cancelled) return;
          setMeta(m);
        }
        if (m.status !== "ready") {
          setStatusNote(null);
          setError(`Document status: ${m.status}`);
          await loadDiagnostics(id, token);
          return;
        }
        await loadReady(id, token);
      } catch (err) {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id, token]);

  async function onReprocess() {
    if (!token || !meta) return;
    setDoc(null);
    setAcceptedOverlay(null);
    setError(null);
    setStatusNote("Reprocessing…");
    await apiFetch(`/v1/documents/${meta.id}/reprocess`, token, { method: "POST", body: "{}" });
    for (let i = 0; i < 80; i++) {
      await new Promise((r) => setTimeout(r, 400));
      const next = (await apiFetch(`/v1/documents/${meta.id}`, token)) as DocMeta;
      setMeta(next);
      if (next.status === "ready") {
        await loadReady(meta.id, token);
        return;
      }
      if (next.status === "failed" || next.status === "cancelled") {
        setError(`Reprocess ${next.status}`);
        await loadDiagnostics(meta.id, token);
        return;
      }
      setStatusNote(`Document status: ${next.status}…`);
    }
    setError("Reprocess timed out");
  }

  async function exportDoc(targetFormat: "markdown" | "html" | "pdf") {
    if (!token || !meta) return;
    const created = (await apiFetch(`/v1/documents/${meta.id}/exports`, token, {
      method: "POST",
      body: JSON.stringify({ targetFormat }),
    })) as { id: string };
    const buf = (await apiFetch(`/v1/documents/${meta.id}/exports/${created.id}/content`, token)) as ArrayBuffer;
    const blob = new Blob([buf]);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    const ext = targetFormat === "html" ? "html" : targetFormat === "pdf" ? "pdf" : "md";
    a.download = `${meta.title || "export"}.${ext}`;
    a.click();
    URL.revokeObjectURL(url);
  }

  async function refreshShareLinks() {
    if (!token || !meta) return;
    const links = (await apiFetch(`/v1/documents/${meta.id}/share-links`, token)) as ShareLink[];
    setShareLinks(links);
  }

  async function openShare() {
    setShareOpen(true);
    setShareMsg(null);
    setPreviewUrl(null);
    await refreshShareLinks();
  }

  async function createShare(e: FormEvent) {
    e.preventDefault();
    if (!token || !meta) return;
    const expiresAt = new Date(Date.now() + expiresHours * 3600_000).toISOString();
    const link = (await apiFetch(`/v1/documents/${meta.id}/share-links`, token, {
      method: "POST",
      body: JSON.stringify({
        expiresAt,
        requireAuth,
        allowDownload,
        allowExport,
        label: "Reader share",
      }),
    })) as ShareLink;
    const preview = (await apiFetch(`/v1/documents/${meta.id}/preview-tokens`, token, {
      method: "POST",
      body: JSON.stringify({ expiresAt, maxViews, allowOriginalDownload: allowDownload }),
    })) as { id: string; token: string };
    setShareMsg(
      `Share token (copy once): ${link.token}\nOpen via API: /v1/shared/${link.token}`
    );
    setPreviewUrl(`${window.location.origin}/preview/${preview.token}`);
    await refreshShareLinks();
  }

  async function revokeShare(linkId: string) {
    if (!token) return;
    await apiFetch(`/v1/share-links/${linkId}`, token, { method: "DELETE" });
    await refreshShareLinks();
  }

  if (!auth.user && !auth.devBypass) {
    return <p className="muted">Sign in to view this document.</p>;
  }

  if (error && meta && (meta.status === "failed" || meta.status === "cancelled" || !doc)) {
    const lastJobError = diagnostics?.jobs?.find((j) => j.lastError)?.lastError;
    return (
      <section className="panel" role="alert">
        <h1>Processing {meta.status === "failed" || meta.status === "cancelled" ? meta.status : "issue"}</h1>
        <p>{error}</p>
        {lastJobError ? <p className="muted">Last job error: {lastJobError}</p> : null}
        <p className="muted">
          Original checksum {meta.originalSha256?.slice(0, 16)}… — originals remain available for recovery.
        </p>
        <div className="org-actions">
          <button type="button" onClick={() => void onReprocess()}>
            Reprocess
          </button>
          <button
            type="button"
            className="secondary"
            onClick={async () => {
              if (!token) return;
              const buf = (await apiFetch(`/v1/documents/${meta.id}/original`, token)) as ArrayBuffer;
              const blob = new Blob([buf]);
              const url = URL.createObjectURL(blob);
              const a = document.createElement("a");
              a.href = url;
              a.download = meta.title || "original";
              a.click();
              URL.revokeObjectURL(url);
            }}
          >
            Download original
          </button>
          <Link to="/library">Back to library</Link>
        </div>
      </section>
    );
  }

  if (error && !meta) {
    return (
      <p role="alert">
        {error} — <Link to="/">Back</Link>
      </p>
    );
  }

  if (!doc || !meta) {
    return <p className="muted">{statusNote ?? "Loading reader…"}</p>;
  }

  return (
    <>
      <div className="org-actions">
        {(exportFormats.length
          ? exportFormats
          : [
              { targetFormat: "markdown", converterId: "cleardoc-to-markdown" },
              { targetFormat: "html", converterId: "cleardoc-to-html-sanitized" },
              { targetFormat: "pdf", converterId: "cleardoc-to-pdf-constrained" },
            ]
        ).map((c) => (
          <button
            key={c.targetFormat}
            type="button"
            className="secondary"
            title={`${c.converterId} · generated artifact`}
            onClick={() => void exportDoc(c.targetFormat as "markdown" | "html" | "pdf")}
          >
            Export {c.targetFormat === "html" ? "HTML" : c.targetFormat === "pdf" ? "PDF" : "Markdown"}
          </button>
        ))}
        <button type="button" className="secondary" onClick={() => void openShare()}>
          Share / preview
        </button>
        <p className="muted export-honesty" style={{ margin: 0, flexBasis: "100%" }}>
          Generated from ClearDoc (<code>artifactKind=generated</code>
          {exportFormats[0]?.converterId ? `; e.g. ${exportFormats.map((c) => c.converterId).join(", ")}` : ""}).
          Download original for byte-identical source. PDF is constrained text layout, not a print engine.
        </p>
      </div>

      {shareOpen && (
        <section className="panel share-panel" aria-label="Share controls">
          <h2>Share &amp; preview</h2>
          <form onSubmit={(e) => void createShare(e)} className="share-form">
            <label>
              Expires in hours
              <input
                type="number"
                min={1}
                max={720}
                value={expiresHours}
                onChange={(e) => setExpiresHours(Number(e.target.value) || 72)}
              />
            </label>
            <label>
              Preview max views
              <input
                type="number"
                min={1}
                max={1000}
                value={maxViews}
                onChange={(e) => setMaxViews(Number(e.target.value) || 50)}
              />
            </label>
            <label className="check">
              <input type="checkbox" checked={requireAuth} onChange={(e) => setRequireAuth(e.target.checked)} /> Require
              auth
            </label>
            <label className="check">
              <input type="checkbox" checked={allowDownload} onChange={(e) => setAllowDownload(e.target.checked)} /> Allow
              original download
            </label>
            <label className="check">
              <input type="checkbox" checked={allowExport} onChange={(e) => setAllowExport(e.target.checked)} /> Allow
              export
            </label>
            <button type="submit">Create share + preview</button>
            <button type="button" className="secondary" onClick={() => setShareOpen(false)}>
              Close
            </button>
          </form>
          {shareMsg && (
            <pre className="share-token" tabIndex={0}>
              {shareMsg}
            </pre>
          )}
          {previewUrl && (
            <p>
              Preview URL:{" "}
              <a href={previewUrl} target="_blank" rel="noreferrer">
                {previewUrl}
              </a>
            </p>
          )}
          <h3>Existing share links</h3>
          <ul className="library-list">
            {shareLinks.map((l) => (
              <li key={l.id}>
                <div>
                  <strong>{l.label || l.id.slice(0, 8)}</strong>
                  <span className="muted">
                    {" "}
                    · expires {l.expiresAt || "none"} · {l.revokedAt ? "revoked" : "active"} · download=
                    {String(l.allowDownload)} · export={String(l.allowExport)}
                  </span>
                </div>
                {!l.revokedAt && (
                  <button type="button" className="secondary" onClick={() => void revokeShare(l.id)}>
                    Revoke
                  </button>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      <ReaderShell
        meta={meta}
        doc={doc}
        originalText={originalText}
        originalError={originalError}
        originalLoading={originalLoading}
        initialFindQuery={initialFind}
        onDownloadOriginal={async () => {
          if (!token) return;
          const buf = (await apiFetch(`/v1/documents/${meta.id}/original`, token)) as ArrayBuffer;
          const blob = new Blob([buf]);
          const url = URL.createObjectURL(blob);
          const a = document.createElement("a");
          a.href = url;
          a.download = meta.title || "original";
          a.click();
          URL.revokeObjectURL(url);
        }}
        onReprocess={() => void onReprocess()}
      />
      {token && (
        <EnrichmentReview
          documentId={meta.id}
          token={token}
          sourceOutline={doc.outline ?? []}
          acceptedOverlay={acceptedOverlay}
          onAccepted={setAcceptedOverlay}
        />
      )}
      {acceptedOverlay?.proposal && (
        <section className="accepted-ai-overlay" aria-label="Accepted AI overlay">
          <h2>
            Accepted overlay <span className="ai-badge">AI-generated</span>
          </h2>
          <p className="muted">
            Kind {acceptedOverlay.kind}. This is a display overlay only — original and ClearDoc source are unchanged.
          </p>
          {typeof acceptedOverlay.proposal.summary === "string" && <p>{acceptedOverlay.proposal.summary}</p>}
        </section>
      )}
    </>
  );
}
