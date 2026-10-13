import { FormEvent, useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { apiFetch, formatApiError } from "../api/client";
import { useAuth } from "../auth/AuthProvider";

type Doc = {
  id: string;
  title: string;
  description: string;
  formatId: string;
  status: string;
  favorite: boolean;
  updatedAt: string;
  deleted?: boolean;
};

/** Render server-sanitized snippet HTML allowing only <mark> (no dangerouslySetInnerHTML). */
function SnippetText({ html }: { html: string }) {
  const parts: ReactNode[] = [];
  const re = /<mark>(.*?)<\/mark>/gi;
  let last = 0;
  let m: RegExpExecArray | null;
  let i = 0;
  while ((m = re.exec(html)) !== null) {
    if (m.index > last) parts.push(decodeBasicEntities(html.slice(last, m.index)));
    parts.push(<mark key={i++}>{decodeBasicEntities(m[1] ?? "")}</mark>);
    last = m.index + m[0].length;
  }
  if (last < html.length) parts.push(decodeBasicEntities(html.slice(last)));
  return <>{parts}</>;
}

function decodeBasicEntities(s: string): string {
  return s
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&");
}

export function LibraryPage() {
  const auth = useAuth();
  const token = auth.getAccessToken();
  const [docs, setDocs] = useState<Doc[]>([]);
  const [status, setStatus] = useState("");
  const [favorite, setFavorite] = useState(false);
  const [sort, setSort] = useState("updated");
  const [q, setQ] = useState("");
  const [searchHits, setSearchHits] = useState<{ documentId: string; title: string; snippetHtml: string }[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function load() {
    if (!token) return;
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (status) params.set("status", status);
      if (favorite) params.set("favorite", "true");
      params.set("sort", sort);
      const data = (await apiFetch(`/v1/library?${params}`, token)) as Doc[];
      setDocs(data);
      setError(null);
    } catch (e) {
      setDocs([]);
      setError(formatApiError(e, "Library"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [token, status, favorite, sort]);

  async function onSearch(e: FormEvent) {
    e.preventDefault();
    if (!token || !q.trim()) return;
    try {
      const data = (await apiFetch(`/v1/search?q=${encodeURIComponent(q.trim())}`, token)) as {
        results: { documentId: string; title: string; snippetHtml: string }[];
      };
      setSearchHits(data.results);
      setError(null);
    } catch (err) {
      setError(formatApiError(err, "Search"));
    }
  }

  async function toggleFavorite(id: string, value: boolean) {
    if (!token) return;
    try {
      await apiFetch(`/v1/documents/${id}/meta`, token, {
        method: "PATCH",
        body: JSON.stringify({ favorite: !value }),
      });
      await load();
    } catch (err) {
      setError(formatApiError(err, "Favorite"));
    }
  }

  if (auth.loading) {
    return (
      <section className="panel">
        <div className="status-bar">
          <span className="status-dot busy" />
          <span>Checking session…</span>
        </div>
      </section>
    );
  }

  if (!auth.user && !auth.devBypass) {
    return (
      <section className="panel access-panel">
        <div className="page-header" style={{ border: 0, padding: 0, margin: 0 }}>
          <div>
            <h1>Library</h1>
            <p className="lede">Sign in to browse workspace documents, favorites, and full-text search.</p>
          </div>
        </div>
        <div className="access-actions">
          <button type="button" onClick={() => void auth.login()}>
            Sign in
          </button>
          <button type="button" className="secondary" onClick={() => void auth.register()}>
            Create account
          </button>
          <Link className="button secondary" to="/">
            Home
          </Link>
        </div>
      </section>
    );
  }

  return (
    <section className="panel">
      <div className="page-header" style={{ border: 0, padding: 0, margin: 0 }}>
        <div>
          <h1>Library</h1>
          <p className="lede">Workspace documents with status filters, favorites, and full-text search. Reopen any item after closing the reader.</p>
        </div>
        <div className="page-header-actions">
          <Link className="button secondary" to="/">
            Ingest
          </Link>
          <button type="button" className="secondary" onClick={() => void load()} disabled={loading}>
            {loading ? "Refreshing…" : "Refresh"}
          </button>
        </div>
      </div>

      <div className="status-bar">
        <span className={`status-dot${error ? " err" : loading ? " busy" : ""}`} />
        <span>
          {loading ? "Loading…" : `${docs.length} document${docs.length === 1 ? "" : "s"}`}
          {status ? ` · status ${status}` : ""}
          {favorite ? " · favorites" : ""}
        </span>
        <span className="muted">Sort: {sort}</span>
      </div>

      <form onSubmit={onSearch} className="library-search">
        <label>
          Search
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Workspace full-text search" />
        </label>
        <button type="submit">Search</button>
      </form>

      {searchHits.length > 0 && (
        <div className="search-results">
          <h2>Search results</h2>
          <ul className="library-list">
            {searchHits.map((h) => (
              <li key={h.documentId}>
                <div className="library-item-main">
                  <Link to={`/documents/${h.documentId}?q=${encodeURIComponent(q.trim())}`}>
                    {decodeBasicEntities(h.title.replace(/<[^>]+>/g, ""))}
                  </Link>
                  <div className="muted library-desc">
                    <SnippetText html={h.snippetHtml} />
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="library-filters">
        <label>
          Status
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Any</option>
            <option value="ready">Ready</option>
            <option value="processing">Processing</option>
            <option value="queued">Queued</option>
            <option value="fetching">Fetching</option>
            <option value="failed">Failed</option>
          </select>
        </label>
        <label>
          Sort
          <select value={sort} onChange={(e) => setSort(e.target.value)}>
            <option value="updated">Updated</option>
            <option value="title">Title</option>
            <option value="created">Created</option>
            <option value="opened">Recently opened</option>
          </select>
        </label>
        <label className="check">
          <input type="checkbox" checked={favorite} onChange={(e) => setFavorite(e.target.checked)} /> Favorites
        </label>
      </div>

      {error && (
        <p className="alert" role="alert">
          {error}
        </p>
      )}

      {!error && docs.length === 0 && !loading ? (
        <div className="library-empty">
          <p>No documents match these filters.</p>
          <p className="muted">Paste, upload, or fetch a file from Ingest to start reading.</p>
          <p>
            <Link to="/">Go to ingest</Link>
          </p>
        </div>
      ) : (
        <ul className="library-list">
          {docs.map((d) => (
            <li key={d.id}>
              <div className="library-item-main">
                <Link to={`/documents/${d.id}`}>{d.title}</Link>
                <div className="library-meta muted">
                  <span className="format-chip">{d.formatId}</span>
                  <span>{d.status}</span>
                  {d.updatedAt ? <span>{formatUpdated(d.updatedAt)}</span> : null}
                </div>
                {d.description ? <div className="muted library-desc">{d.description}</div> : null}
              </div>
              <button type="button" className="secondary" onClick={() => void toggleFavorite(d.id, d.favorite)}>
                {d.favorite ? "★ Favorited" : "☆ Favorite"}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function formatUpdated(iso: string): string {
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
  } catch {
    return iso;
  }
}
