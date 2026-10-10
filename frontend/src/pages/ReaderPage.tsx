import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiFetch } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import type { ClearDoc } from "../reader/ClearDocView";
import { ReaderShell } from "../reader/ReaderShell";

type DocMeta = {
  id: string;
  title: string;
  status: string;
  originalSha256: string;
  formatId: string;
  currentRevisionId?: string;
};

export function ReaderPage() {
  const { id } = useParams();
  const auth = useAuth();
  const token = auth.getAccessToken();
  const [meta, setMeta] = useState<DocMeta | null>(null);
  const [doc, setDoc] = useState<ClearDoc | null>(null);
  const [originalText, setOriginalText] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id || !token) return;
    let cancelled = false;
    (async () => {
      try {
        let m = (await apiFetch(`/v1/documents/${id}`, token)) as DocMeta;
        if (cancelled) return;
        setMeta(m);
        for (let i = 0; i < 80 && m.status !== "ready" && m.status !== "failed" && m.status !== "cancelled"; i++) {
          setError(`Document status: ${m.status}…`);
          await new Promise((r) => setTimeout(r, 500));
          m = (await apiFetch(`/v1/documents/${id}`, token)) as DocMeta;
          if (cancelled) return;
          setMeta(m);
        }
        if (m.status !== "ready") {
          setError(`Document status: ${m.status}`);
          return;
        }
        setError(null);
        const cleardoc = (await apiFetch(`/v1/documents/${id}/cleardoc`, token)) as ClearDoc;
        if (cancelled) return;
        setDoc(cleardoc);
        const buf = (await apiFetch(`/v1/documents/${id}/original`, token)) as ArrayBuffer;
        if (!cancelled) setOriginalText(new TextDecoder().decode(buf));
      } catch (err) {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id, token]);

  if (!auth.user && !auth.devBypass) {
    return <p className="muted">Sign in to view this document.</p>;
  }

  if (error) {
    return (
      <p role="alert">
        {error} — <Link to="/">Back</Link>
      </p>
    );
  }

  if (!doc || !meta) {
    return <p className="muted">Loading reader…</p>;
  }

  return (
    <ReaderShell
      meta={meta}
      doc={doc}
      originalText={originalText}
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
      onReprocess={async () => {
        if (!token || !meta) return;
        setDoc(null);
        setError("Reprocessing…");
        await apiFetch(`/v1/documents/${meta.id}/reprocess`, token, { method: "POST", body: "{}" });
        for (let i = 0; i < 80; i++) {
          await new Promise((r) => setTimeout(r, 400));
          const next = (await apiFetch(`/v1/documents/${meta.id}`, token)) as DocMeta;
          setMeta(next);
          if (next.status === "ready") {
            const cleardoc = (await apiFetch(`/v1/documents/${meta.id}/cleardoc`, token)) as ClearDoc;
            setDoc(cleardoc);
            setError(null);
            return;
          }
          if (next.status === "failed" || next.status === "cancelled") {
            setError(`Reprocess ${next.status}`);
            return;
          }
          setError(`Document status: ${next.status}…`);
        }
        setError("Reprocess timed out");
      }}
    />
  );
}
