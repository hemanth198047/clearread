import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiFetch } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { ClearDocView, type ClearDoc } from "../reader/ClearDocView";

type DocMeta = {
  id: string;
  title: string;
  status: string;
  originalSha256: string;
  formatId: string;
};

export function ReaderPage() {
  const { id } = useParams();
  const auth = useAuth();
  const token = auth.getAccessToken();
  const [meta, setMeta] = useState<DocMeta | null>(null);
  const [doc, setDoc] = useState<ClearDoc | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id || !token) return;
    let cancelled = false;
    (async () => {
      try {
        const m = (await apiFetch(`/v1/documents/${id}`, token)) as DocMeta;
        if (cancelled) return;
        setMeta(m);
        if (m.status !== "ready") {
          setError(`Document status: ${m.status}`);
          return;
        }
        const cleardoc = (await apiFetch(`/v1/documents/${id}/cleardoc`, token)) as ClearDoc;
        if (!cancelled) setDoc(cleardoc);
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
    <div>
      <p className="muted">
        <Link to="/">← Library</Link> · {meta.formatId} · sha256 {meta.originalSha256.slice(0, 12)}…
        {" · "}
        <a href={`${import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080"}/v1/documents/${meta.id}/original`}
           onClick={async (e) => {
             e.preventDefault();
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
        </a>
      </p>
      <ClearDocView doc={doc} />
    </div>
  );
}
