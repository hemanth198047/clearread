import { useEffect, useMemo, useState, type ReactNode, type RefObject } from "react";
import type { ReaderPreferences } from "./preferences";
import { normalizeSegmentationMode } from "./preferences";
import { coerceBody, segmentBody } from "./segmentation";

function safeHref(href: unknown): string {
  const raw = String(href ?? "#");
  const lower = raw.toLowerCase().trim();
  if (
    lower.startsWith("https:") ||
    lower.startsWith("http:") ||
    lower.startsWith("mailto:") ||
    lower.startsWith("#") ||
    lower.startsWith("/")
  ) {
    return raw;
  }
  return "#";
}

export type ClearDoc = {
  metadata: { title: string };
  outline: { level: number; text: string; nodeId: string }[];
  warnings: { code: string; message: string; nodeId?: string }[];
  body: Record<string, unknown>[];
};

type Inline = Record<string, unknown>;

function highlightText(value: string, query: string, currentIndex: number, hitCounter: { n: number }): ReactNode {
  if (!query) return value;
  const lower = value.toLowerCase();
  const q = query.toLowerCase();
  const parts: ReactNode[] = [];
  let start = 0;
  let idx = lower.indexOf(q, start);
  while (idx >= 0) {
    if (idx > start) parts.push(value.slice(start, idx));
    const hitIdx = hitCounter.n++;
    parts.push(
      <mark key={`h-${hitIdx}`} className={hitIdx === currentIndex ? "cr-hit current" : "cr-hit"} data-hit={hitIdx}>
        {value.slice(idx, idx + query.length)}
      </mark>
    );
    start = idx + query.length;
    idx = lower.indexOf(q, start);
  }
  if (start < value.length) parts.push(value.slice(start));
  return parts.length ? parts : value;
}

function renderInlines(
  nodes: Inline[] | undefined,
  query: string,
  currentHit: number,
  hitCounter: { n: number }
): ReactNode {
  if (!nodes) return null;
  return nodes.map((n, i) => {
    if (n.type === "text") {
      let el: ReactNode = highlightText(String(n.value ?? ""), query, currentHit, hitCounter);
      const marks = (n.marks as string[] | undefined) ?? [];
      if (marks.includes("bold")) el = <strong key={`b${i}`}>{el}</strong>;
      if (marks.includes("italic")) el = <em key={`i${i}`}>{el}</em>;
      return <span key={i}>{el}</span>;
    }
    if (n.type === "code") {
      return <code key={i}>{highlightText(String(n.value ?? ""), query, currentHit, hitCounter)}</code>;
    }
    if (n.type === "hardBreak") return <br key={i} />;
    if (n.type === "link") {
      return (
        <a key={i} href={safeHref(n.href)} rel="noopener noreferrer">
          {renderInlines(n.children as Inline[], query, currentHit, hitCounter)}
        </a>
      );
    }
    return null;
  });
}

function CodeBlock({
  id,
  text,
  language,
  showLineNumbers,
}: {
  id?: string;
  text: string;
  language?: string;
  showLineNumbers: boolean;
}) {
  const lines = text.replace(/\n$/, "").split("\n");
  if (!showLineNumbers) {
    return (
      <pre id={id} data-language={language}>
        <code>{text}</code>
      </pre>
    );
  }
  return (
    <pre id={id} data-language={language} className="code-with-lines">
      <span className="gutter" aria-hidden="true">
        {lines.map((_, i) => (
          <div key={i}>{i + 1}</div>
        ))}
      </span>
      <code>{text}</code>
    </pre>
  );
}

function Block({
  node,
  query,
  currentHit,
  hitCounter,
  prefs,
  collapsed,
  onToggleCollapse,
  showInferred,
}: {
  node: Record<string, unknown>;
  query: string;
  currentHit: number;
  hitCounter: { n: number };
  prefs: ReaderPreferences;
  collapsed: Set<string>;
  onToggleCollapse: (id: string) => void;
  showInferred: boolean;
}) {
  const type = String(node.type);
  const id = node.nodeId ? String(node.nodeId) : undefined;
  const provenance = node.provenance as { kind?: string } | undefined;
  const inferred = provenance?.kind === "inferred";

  if (type === "heading") {
    const level = Math.min(6, Math.max(1, Number(node.level ?? 2)));
    const isCollapsed = id ? collapsed.has(id) : false;
    const children = (
      <span className="section-collapse">
        {prefs.sectionCollapsing && id && (
          <button
            type="button"
            className="collapse-toggle"
            aria-expanded={!isCollapsed}
            onClick={() => onToggleCollapse(id)}
          >
            {isCollapsed ? "+" : "−"}
          </button>
        )}
        <span>
          {renderInlines(node.children as Inline[], query, currentHit, hitCounter)}
          {showInferred && inferred && <span className="badge-inferred"> inferred</span>}
        </span>
      </span>
    );
    if (level === 1) return <h1 id={id}>{children}</h1>;
    if (level === 2) return <h2 id={id}>{children}</h2>;
    if (level === 3) return <h3 id={id}>{children}</h3>;
    if (level === 4) return <h4 id={id}>{children}</h4>;
    if (level === 5) return <h5 id={id}>{children}</h5>;
    return <h6 id={id}>{children}</h6>;
  }
  if (type === "paragraph") {
    return (
      <p id={id}>
        {renderInlines(node.children as Inline[], query, currentHit, hitCounter)}
        {showInferred && inferred && <span className="badge-inferred"> inferred</span>}
      </p>
    );
  }
  if (type === "codeBlock") {
    const language = node.language ? String(node.language) : undefined;
    const isMermaid = language?.trim().split(/\s+/)[0]?.toLowerCase() === "mermaid";
    return (
      <div className={isMermaid ? "mermaid-source" : undefined}>
        {isMermaid ? (
          <p className="mermaid-note muted">
            Mermaid source (not executed). A text outline appears below when available.
          </p>
        ) : null}
        <CodeBlock
          id={id}
          text={String(node.text ?? "")}
          language={language}
          showLineNumbers={prefs.showLineNumbers}
        />
      </div>
    );
  }
  if (type === "blockquote") {
    return (
      <blockquote id={id}>
        {(node.children as Record<string, unknown>[] | undefined)?.map((c, i) => (
          <Block
            key={i}
            node={c}
            query={query}
            currentHit={currentHit}
            hitCounter={hitCounter}
            prefs={prefs}
            collapsed={collapsed}
            onToggleCollapse={onToggleCollapse}
            showInferred={showInferred}
          />
        ))}
      </blockquote>
    );
  }
  if (type === "bulletList" || type === "orderedList") {
    const ListTag = type === "bulletList" ? "ul" : "ol";
    return (
      <ListTag id={id}>
        {((node.items as Record<string, unknown>[]) ?? []).map((item, i) => (
          <li key={i}>
            {((item.children as Record<string, unknown>[]) ?? []).map((c, j) => (
              <Block
                key={j}
                node={c}
                query={query}
                currentHit={currentHit}
                hitCounter={hitCounter}
                prefs={prefs}
                collapsed={collapsed}
                onToggleCollapse={onToggleCollapse}
                showInferred={showInferred}
              />
            ))}
          </li>
        ))}
      </ListTag>
    );
  }
  if (type === "thematicBreak") {
    return <hr id={id} />;
  }
  if (type === "dataTable") {
    const headers = (node.headers as string[]) ?? [];
    const rows = (node.rows as string[][]) ?? [];
    const q = query.toLowerCase();
    const filtered = q
      ? rows.filter((row) => row.some((cell) => String(cell).toLowerCase().includes(q)))
      : rows;
    const visible = filtered.slice(0, 200);
    return (
      <div id={id} className="data-table-wrap">
        <div className="data-table-meta muted">
          {headers.length} columns · {filtered.length} rows
          {filtered.length > 200 ? " (showing 200)" : ""}
          {node.delimiter ? ` · delimiter ${JSON.stringify(node.delimiter)}` : ""}
        </div>
        <div className="data-table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                {headers.map((h, i) => (
                  <th key={i} scope="col">
                    {highlightText(String(h), query, currentHit, hitCounter)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {visible.map((row, ri) => (
                <tr key={ri}>
                  {headers.map((_, ci) => (
                    <td key={ci}>{highlightText(String(row[ci] ?? ""), query, currentHit, hitCounter)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    );
  }
  if (type === "treeNode") {
    return (
      <TreeView
        node={node}
        query={query}
        currentHit={currentHit}
        hitCounter={hitCounter}
        depth={0}
      />
    );
  }
  if (type === "formattedSource") {
    const language = node.language ? String(node.language) : "source";
    const isOutline = language === "mermaid-outline";
    return (
      <div className="formatted-source">
        <div className="formatted-source-bar">
          <span>{isOutline ? "Mermaid text fallback" : language}</span>
          {showInferred && node.provenance && String((node.provenance as { kind?: string }).kind) === "generated" ? (
            <span className="badge-inferred"> generated</span>
          ) : null}
          <button
            type="button"
            className="collapse-toggle"
            onClick={() => navigator.clipboard?.writeText(String(node.text ?? ""))}
          >
            Copy
          </button>
        </div>
        <CodeBlock
          id={id}
          text={String(node.text ?? "")}
          language={language}
          showLineNumbers={prefs.showLineNumbers && !isOutline}
        />
      </div>
    );
  }
  return (
    <p className="muted" id={id}>
      Unsupported block: {type}
    </p>
  );
}

function TreeView({
  node,
  query,
  currentHit,
  hitCounter,
  depth,
}: {
  node: Record<string, unknown>;
  query: string;
  currentHit: number;
  hitCounter: { n: number };
  depth: number;
}) {
  const [open, setOpen] = useState(depth < 2);
  const children = (node.children as Record<string, unknown>[] | undefined) ?? [];
  const hasChildren = children.length > 0;
  const id = node.nodeId ? String(node.nodeId) : undefined;
  return (
    <div id={id} className="tree-node" style={{ marginLeft: depth === 0 ? 0 : "0.85rem" }}>
      <div className="tree-row">
        {hasChildren ? (
          <button type="button" className="collapse-toggle" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
            {open ? "▾" : "▸"}
          </button>
        ) : (
          <span className="tree-leaf-spacer" aria-hidden="true" />
        )}
        <span className="tree-label">{highlightText(String(node.label ?? ""), query, currentHit, hitCounter)}</span>
        {node.valueType ? <span className="tree-type">{String(node.valueType)}</span> : null}
        {node.valuePreview != null ? (
          <span className="tree-preview">
            {highlightText(String(node.valuePreview), query, currentHit, hitCounter)}
          </span>
        ) : null}
      </div>
      {open &&
        hasChildren &&
        children.map((c, i) => (
          <TreeView key={String(c.nodeId ?? i)} node={c} query={query} currentHit={currentHit} hitCounter={hitCounter} depth={depth + 1} />
        ))}
    </div>
  );
}

/** Apply section collapsing: hide blocks until next heading of same/higher level. */
function applyCollapse(body: Record<string, unknown>[], collapsed: Set<string>): Record<string, unknown>[] {
  if (collapsed.size === 0) return body;
  const out: Record<string, unknown>[] = [];
  let hidingUntilLevel: number | null = null;
  for (const node of body) {
    if (node.type === "heading") {
      const level = Number(node.level ?? 2);
      const id = node.nodeId ? String(node.nodeId) : "";
      if (hidingUntilLevel != null && level > hidingUntilLevel) {
        continue;
      }
      hidingUntilLevel = null;
      out.push(node);
      if (id && collapsed.has(id)) {
        hidingUntilLevel = level;
      }
      continue;
    }
    if (hidingUntilLevel != null) continue;
    out.push(node);
  }
  return out;
}

const CHUNK = 80;

export function ClearDocView({
  doc,
  prefs,
  query = "",
  currentHit = -1,
  scrollParentRef,
}: {
  doc: ClearDoc;
  prefs: ReaderPreferences;
  query?: string;
  currentHit?: number;
  scrollParentRef?: RefObject<HTMLElement | null>;
}) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [visibleCount, setVisibleCount] = useState(CHUNK);
  const hitCounter = { n: 0 };
  const segmentation = normalizeSegmentationMode(prefs.segmentation);
  const showInferred = segmentation === "detailed";
  const sourceBody = coerceBody(doc);

  const body = useMemo(() => {
    const segmented = segmentBody(doc, segmentation);
    // Belt-and-suspenders: never blank a non-empty ClearDoc (wrong mode key / filter / collapse).
    const safe =
      segmented.length === 0 && sourceBody.length > 0 ? sourceBody.slice() : segmented;
    return applyCollapse(safe, collapsed);
  }, [doc, segmentation, collapsed, sourceBody]);

  // Reset collapse + progressive reveal when doc or segmentation mode changes (Detailed must show content).
  useEffect(() => {
    setCollapsed(new Set());
    setVisibleCount(sourceBody.length === 0 ? 0 : Math.min(Math.max(sourceBody.length, 1), CHUNK));
  }, [doc, segmentation, sourceBody.length]);

  const displayBody = body.length === 0 && sourceBody.length > 0 ? sourceBody : body;
  const slice = displayBody.slice(0, Math.max(visibleCount, displayBody.length > 0 ? 1 : 0));

  const onToggleCollapse = (id: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  // Progressive reveal listens on the scroll parent (.reader-main), not the article
  useEffect(() => {
    const el = scrollParentRef?.current;
    if (!el) return;
    const onScroll = () => {
      if (el.scrollTop + el.clientHeight > el.scrollHeight - 400 && visibleCount < displayBody.length) {
        setVisibleCount((c) => Math.min(displayBody.length, c + CHUNK));
      }
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => el.removeEventListener("scroll", onScroll);
  }, [scrollParentRef, displayBody.length, visibleCount]);

  // Truly empty only when the source ClearDoc has no blocks (never for Detailed on non-empty docs).
  const showEmpty = displayBody.length === 0 && sourceBody.length === 0;

  return (
    <article
      id="reader-article"
      className="reader-article"
      data-segmentation={segmentation}
      aria-label={doc.metadata?.title ?? "Document"}
    >
      <h1>{doc.metadata?.title ?? "Document"}</h1>
      {doc.warnings?.length > 0 && (
        <aside className="reader-warnings" aria-label="Processing notes">
          <p className="muted">
            {doc.warnings.length} processing note{doc.warnings.length === 1 ? "" : "s"} — generated structure may differ
            from source.
          </p>
          <ul>
            {doc.warnings.map((w, i) => (
              <li key={`${w.code}-${i}`}>
                <code>{w.code}</code> {w.message}
              </li>
            ))}
          </ul>
        </aside>
      )}
      {showEmpty ? (
        <p className="reader-empty" role="status">
          No blocks to display for this segmentation mode
          {segmentation !== "detailed" ? " — try Balanced or Detailed" : ""}. The document body is empty after
          filtering, or the source had no structured content.
        </p>
      ) : (
        slice.map((node, i) => (
          <Block
            key={String(node.nodeId ?? i)}
            node={node}
            query={query}
            currentHit={currentHit}
            hitCounter={hitCounter}
            prefs={prefs}
            collapsed={collapsed}
            onToggleCollapse={onToggleCollapse}
            showInferred={showInferred}
          />
        ))
      )}
      {visibleCount < displayBody.length && (
        <p style={{ fontFamily: "var(--reader-font-ui)", color: "var(--reader-muted)" }}>
          <button type="button" className="collapse-toggle" onClick={() => setVisibleCount(displayBody.length)}>
            Show remaining {displayBody.length - visibleCount} blocks
          </button>
        </p>
      )}
    </article>
  );
}

export function countHits(doc: ClearDoc, query: string, prefs: ReaderPreferences): number {
  if (!query) return 0;
  const q = query.toLowerCase();
  let n = 0;
  const walk = (nodes: unknown) => {
    if (!nodes) return;
    if (typeof nodes === "string") {
      let i = nodes.toLowerCase().indexOf(q);
      while (i >= 0) {
        n++;
        i = nodes.toLowerCase().indexOf(q, i + q.length);
      }
      return;
    }
    if (Array.isArray(nodes)) {
      nodes.forEach(walk);
      return;
    }
    if (typeof nodes === "object") {
      const o = nodes as Record<string, unknown>;
      if (typeof o.value === "string") walk(o.value);
      if (typeof o.text === "string") walk(o.text);
      if (typeof o.label === "string") walk(o.label);
      if (typeof o.valuePreview === "string") walk(o.valuePreview);
      walk(o.children);
      walk(o.items);
      walk(o.headers);
      walk(o.rows);
    }
  };
  walk(segmentBody(doc, prefs.segmentation));
  return n;
}
