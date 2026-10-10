import type { ReactNode } from "react";

export type ClearDoc = {
  metadata: { title: string };
  outline: { level: number; text: string; nodeId: string }[];
  warnings: { code: string; message: string; nodeId?: string }[];
  body: Record<string, unknown>[];
};

type Inline = Record<string, unknown>;

function renderInlines(nodes: Inline[] | undefined): ReactNode {
  if (!nodes) return null;
  return nodes.map((n, i) => {
    if (n.type === "text") {
      let el: ReactNode = String(n.value ?? "");
      const marks = (n.marks as string[] | undefined) ?? [];
      if (marks.includes("bold")) el = <strong key={`b${i}`}>{el}</strong>;
      if (marks.includes("italic")) el = <em key={`i${i}`}>{el}</em>;
      return <span key={i}>{el}</span>;
    }
    if (n.type === "code") return <code key={i}>{String(n.value ?? "")}</code>;
    if (n.type === "hardBreak") return <br key={i} />;
    if (n.type === "link") {
      return (
        <a key={i} href={String(n.href ?? "#")}>
          {renderInlines(n.children as Inline[])}
        </a>
      );
    }
    return null;
  });
}

function Block({ node }: { node: Record<string, unknown> }) {
  const type = String(node.type);
  const id = node.nodeId ? String(node.nodeId) : undefined;
  const provenance = node.provenance as { kind?: string } | undefined;
  const inferred = provenance?.kind === "inferred";

  if (type === "heading") {
    const level = Math.min(6, Math.max(1, Number(node.level ?? 2)));
    const children = (
      <>
        {renderInlines(node.children as Inline[])}
        {inferred && <span className="badge"> inferred</span>}
      </>
    );
    if (level === 1) return <h1 id={id}>{children}</h1>;
    if (level === 2) return <h2 id={id}>{children}</h2>;
    if (level === 3) return <h3 id={id}>{children}</h3>;
    if (level === 4) return <h4 id={id}>{children}</h4>;
    if (level === 5) return <h5 id={id}>{children}</h5>;
    return <h6 id={id}>{children}</h6>;
  }
  if (type === "paragraph") {
    return <p id={id}>{renderInlines(node.children as Inline[])}</p>;
  }
  if (type === "codeBlock") {
    return (
      <pre id={id}>
        <code>{String(node.text ?? "")}</code>
      </pre>
    );
  }
  if (type === "blockquote") {
    return (
      <blockquote id={id}>
        {(node.children as Record<string, unknown>[] | undefined)?.map((c, i) => (
          <Block key={i} node={c} />
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
              <Block key={j} node={c} />
            ))}
          </li>
        ))}
      </ListTag>
    );
  }
  if (type === "thematicBreak") {
    return <hr id={id} />;
  }
  return (
    <p className="muted" id={id}>
      Unsupported block: {type}
    </p>
  );
}

export function ClearDocView({ doc }: { doc: ClearDoc }) {
  return (
    <div className="reader-layout">
      <nav className="toc" aria-label="Table of contents">
        <h2>Contents</h2>
        {doc.outline.length === 0 ? (
          <p className="muted">No headings</p>
        ) : (
          <ol>
            {doc.outline.map((o) => (
              <li key={o.nodeId} style={{ marginLeft: `${(o.level - 1) * 0.6}rem` }}>
                <a href={`#${o.nodeId}`}>{o.text}</a>
              </li>
            ))}
          </ol>
        )}
      </nav>
      <article className="reader-article">
        <h1>{doc.metadata?.title ?? "Document"}</h1>
        {doc.warnings?.length > 0 && (
          <p className="muted">
            {doc.warnings.length} processing note{doc.warnings.length === 1 ? "" : "s"} (structure may be inferred).
          </p>
        )}
        {doc.body.map((node, i) => (
          <Block key={i} node={node} />
        ))}
      </article>
    </div>
  );
}
