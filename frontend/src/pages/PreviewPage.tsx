import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
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
        const cleardoc = await fetch(`/v1/preview/${token}/cleardoc`).then(async (r) => {
          if (!r.ok) throw new Error(await r.text());
          return r.json();
        });
        setDoc(cleardoc);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    })();
  }, [token]);

  if (!token) return <p role="alert">Missing preview token</p>;
  if (error) return <p role="alert">{error}</p>;
  if (!doc) return <p className="muted">Loading preview…</p>;
  return (
    <section className="panel">
      <p className="muted">Anonymous preview (TTL-bound token). Original download denied unless explicitly granted.</p>
      <ClearDocView doc={doc} prefs={DEFAULT_PREFERENCES} />
    </section>
  );
}
