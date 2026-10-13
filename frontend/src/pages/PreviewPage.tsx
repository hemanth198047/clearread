import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiBase, formatApiError } from "../api/client";
import type { ClearDoc } from "../reader/ClearDocView";
import { ClearDocView } from "../reader/ClearDocView";
import { DEFAULT_PREFERENCES } from "../reader/preferences";

export function PreviewPage() {
  const { token } = useParams();
  const [doc, setDoc] = useState<ClearDoc | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    (async () => {
      try {
        const cleardoc = await fetch(`${apiBase}/v1/preview/${token}/cleardoc`).then(async (r) => {
          if (!r.ok) throw new Error((await r.text()) || r.statusText);
          return r.json();
        });
        setDoc(cleardoc as ClearDoc);
      } catch (e) {
        setError(formatApiError(e, "Preview"));
      }
    })();
  }, [token]);

  if (!token) {
    return (
      <section className="panel" role="alert">
        <h1>Missing preview token</h1>
        <Link to="/">Home</Link>
      </section>
    );
  }
  if (error) {
    return (
      <section className="panel" role="alert">
        <h1>Preview unavailable</h1>
        <p className="alert">{error}</p>
        <Link className="button secondary" to="/">
          Home
        </Link>
      </section>
    );
  }
  if (!doc) {
    return (
      <section className="panel">
        <div className="status-bar">
          <span className="status-dot busy" />
          <span>Loading preview…</span>
        </div>
      </section>
    );
  }
  return (
    <section className="panel">
      <div className="page-header" style={{ border: 0, padding: 0, margin: 0 }}>
        <div>
          <h1>Shared preview</h1>
          <p className="lede">Anonymous preview (TTL-bound token). Original download denied unless explicitly granted.</p>
        </div>
      </div>
      <ClearDocView doc={doc} prefs={DEFAULT_PREFERENCES} />
    </section>
  );
}
