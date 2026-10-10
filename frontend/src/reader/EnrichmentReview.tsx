import { useEffect, useState } from "react";
import { apiFetch } from "../api/client";

type Enrichment = {
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

type AiStatus = {
  aiEnabled: boolean;
  aiProviderId: string;
  usageToday: number;
  aiDailyQuota: number;
  documentReady: boolean;
};

const KINDS = [
  { id: "headings", label: "Suggested headings" },
  { id: "segmentation", label: "Segmentation" },
  { id: "summary", label: "Summary / key points" },
  { id: "glossary", label: "Glossary" },
] as const;

type Props = {
  documentId: string;
  token: string;
  sourceOutline: { level: number; text: string; nodeId: string }[];
  acceptedOverlay: Enrichment | null;
  onAccepted: (enrichment: Enrichment | null) => void;
};

export function EnrichmentReview({ documentId, token, sourceOutline, acceptedOverlay, onAccepted }: Props) {
  const [status, setStatus] = useState<AiStatus | null>(null);
  const [items, setItems] = useState<Enrichment[]>([]);
  const [selected, setSelected] = useState<Enrichment | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    const s = (await apiFetch(`/v1/documents/${documentId}/enrichments/status`, token)) as AiStatus;
    setStatus(s);
    const list = (await apiFetch(`/v1/documents/${documentId}/enrichments`, token)) as Enrichment[];
    setItems(list);
    if (selected) {
      const next = list.find((e) => e.id === selected.id) ?? null;
      setSelected(next);
    }
  }

  useEffect(() => {
    void refresh().catch((e) => setError(e instanceof Error ? e.message : String(e)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentId, token]);

  async function requestKind(kind: string) {
    setBusy(true);
    setError(null);
    try {
      const created = (await apiFetch(`/v1/documents/${documentId}/enrichments`, token, {
        method: "POST",
        body: JSON.stringify({ kind }),
      })) as Enrichment;
      setSelected(created);
      for (let i = 0; i < 40; i++) {
        await new Promise((r) => setTimeout(r, 400));
        const got = (await apiFetch(`/v1/documents/${documentId}/enrichments/${created.id}`, token)) as Enrichment;
        setSelected(got);
        if (got.status === "ready" || got.status === "failed" || got.status === "accepted") break;
      }
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function accept() {
    if (!selected) return;
    setBusy(true);
    try {
      const next = (await apiFetch(`/v1/documents/${documentId}/enrichments/${selected.id}/accept`, token, {
        method: "POST",
        body: "{}",
      })) as Enrichment;
      setSelected(next);
      onAccepted(next);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function reject() {
    if (!selected) return;
    setBusy(true);
    try {
      const next = (await apiFetch(`/v1/documents/${documentId}/enrichments/${selected.id}/reject`, token, {
        method: "POST",
        body: "{}",
      })) as Enrichment;
      setSelected(next);
      if (acceptedOverlay?.id === next.id) onAccepted(null);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  if (!status) {
    return <p className="muted">Loading AI status…</p>;
  }

  if (!status.aiEnabled) {
    return (
      <aside className="enrichment-panel" aria-label="AI enrichment">
        <h2>AI enrichment</h2>
        <p className="muted">
          Disabled by organization policy. Enable under Admin → Optional AI enrichment. The reader works without AI.
        </p>
      </aside>
    );
  }

  return (
    <aside className="enrichment-panel" aria-label="AI enrichment review">
      <h2>AI enrichment review</h2>
      <p className="muted">
        Derived artifacts only — source ClearDoc is never silently mutated. Provider{" "}
        <code>{status.aiProviderId}</code> · usage {status.usageToday}/{status.aiDailyQuota}
      </p>
      {error && <p role="alert">{error}</p>}

      <div className="enrichment-actions">
        {KINDS.map((k) => (
          <button key={k.id} type="button" className="secondary" disabled={busy} onClick={() => void requestKind(k.id)}>
            {k.label}
          </button>
        ))}
      </div>

      {items.length > 0 && (
        <ul className="enrichment-list">
          {items.map((e) => (
            <li key={e.id}>
              <button type="button" className="linkish" onClick={() => setSelected(e)}>
                <span className="ai-badge">AI-generated</span> {e.kind} · {e.status}
              </button>
            </li>
          ))}
        </ul>
      )}

      {selected && (
        <div className="enrichment-compare">
          <div className="compare-col">
            <h3>Current structure</h3>
            <ol>
              {sourceOutline.slice(0, 12).map((o) => (
                <li key={o.nodeId} style={{ marginLeft: `${(o.level - 1) * 0.75}rem` }}>
                  {o.text}
                </li>
              ))}
            </ol>
            {sourceOutline.length === 0 && <p className="muted">No outline headings in source.</p>}
          </div>
          <div className="compare-col">
            <h3>
              AI proposal <span className="ai-badge">{selected.generatedLabel}</span>
            </h3>
            <p className="muted">
              {selected.providerId}/{selected.modelId} · template {selected.promptVersion} · {selected.status}
            </p>
            {selected.lastError && <p role="alert">{selected.lastError}</p>}
            <ProposalView proposal={selected.proposal} />
            {selected.validation?.warnings && selected.validation.warnings.length > 0 && (
              <p className="muted">Warnings: {selected.validation.warnings.join("; ")}</p>
            )}
          </div>
        </div>
      )}

      {selected?.status === "ready" && (
        <div className="enrichment-actions">
          <button type="button" disabled={busy} onClick={() => void accept()}>
            Accept proposal (display overlay)
          </button>
          <button type="button" className="secondary" disabled={busy} onClick={() => void reject()}>
            Reject
          </button>
        </div>
      )}

      {acceptedOverlay && (
        <p className="status-line">
          Displaying accepted AI overlay for <strong>{acceptedOverlay.kind}</strong>. Source document unchanged.
        </p>
      )}
    </aside>
  );
}

function ProposalView({ proposal }: { proposal: Record<string, unknown> | null }) {
  if (!proposal) return <p className="muted">No proposal yet.</p>;
  if (typeof proposal.summary === "string") {
    return (
      <>
        <p>{proposal.summary}</p>
        {Array.isArray(proposal.keyPoints) && (
          <ul>
            {(proposal.keyPoints as string[]).map((p, i) => (
              <li key={i}>{p}</li>
            ))}
          </ul>
        )}
      </>
    );
  }
  if (Array.isArray(proposal.suggestions)) {
    return (
      <ul>
        {(proposal.suggestions as { suggestedText?: string; blockNodeId?: string }[]).map((s, i) => (
          <li key={i}>
            {s.suggestedText} <span className="muted">← {s.blockNodeId}</span>
          </li>
        ))}
      </ul>
    );
  }
  if (Array.isArray(proposal.segments)) {
    return (
      <ul>
        {(proposal.segments as { title?: string; blockNodeIds?: string[] }[]).map((s, i) => (
          <li key={i}>
            {s.title} <span className="muted">({(s.blockNodeIds ?? []).length} blocks)</span>
          </li>
        ))}
      </ul>
    );
  }
  if (Array.isArray(proposal.terms)) {
    return (
      <ul>
        {(proposal.terms as { term?: string; definition?: string }[]).map((t, i) => (
          <li key={i}>
            <strong>{t.term}</strong> — {t.definition}
          </li>
        ))}
      </ul>
    );
  }
  return <pre className="enrichment-json">{JSON.stringify(proposal, null, 2)}</pre>;
}
