import { FormEvent, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../api/client";
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

  async function load() {
    if (!token) return;
    const params = new URLSearchParams();
    if (status) params.set("status", status);
    if (favorite) params.set("favorite", "true");
    params.set("sort", sort);
    const data = (await apiFetch(`/v1/library?${params}`, token)) as Doc[];
    setDocs(data);
  }

  useEffect(() => {
    load().catch((e) => setError(String(e)));
  }, [token, status, favorite, sort]);

  async function onSearch(e: FormEvent) {
    e.preventDefault();
    if (!token || !q.trim()) return;
    const data = (await apiFetch(`/v1/search?q=${encodeURIComponent(q.trim())}`, token)) as {
      results: { documentId: string; title: string; snippetHtml: string }[];
    };
    setSearchHits(data.results);
  }

  async function toggleFavorite(id: string, value: boolean) {
    if (!token) return;
    await apiFetch(`/v1/documents/${id}/meta`, token, {
      method: "PATCH",
      body: JSON.stringify({ favorite: !value }),
    });
    await load();
  }

  if (!auth.user && !auth.devBypass) {
    return <p className="muted">Sign in to browse the library.</p>;
  }

  return (
    <section className="panel">
      <h1>Library</h1>
      <p className="muted">Workspace documents with status filters, favorites, and full-text search.</p>

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
          <ul>
            {searchHits.map((h) => (
              <li key={h.documentId}>
                <Link to={`/documents/${h.documentId}`} dangerouslySetInnerHTML={{ __html: h.title }} />
                <div className="muted" dangerouslySetInnerHTML={{ __html: h.snippetHtml }} />
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

      {error && <p role="alert">{error}</p>}

      <ul className="library-list">
        {docs.map((d) => (
          <li key={d.id}>
            <div>
              <Link to={`/documents/${d.id}`}>{d.title}</Link>
              <span className="muted">
                {" "}
                · {d.formatId} · {d.status}
              </span>
              {d.description ? <div className="muted">{d.description}</div> : null}
            </div>
            <button type="button" className="secondary" onClick={() => void toggleFavorite(d.id, d.favorite)}>
              {d.favorite ? "★ Favorited" : "☆ Favorite"}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
