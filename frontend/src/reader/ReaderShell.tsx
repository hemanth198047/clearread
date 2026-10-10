import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { ClearDocView, countHits, type ClearDoc } from "./ClearDocView";
import { fontFamilyCss, SCROLL_KEY_PREFIX, type ReaderPreferences } from "./preferences";
import { useReaderPreferences } from "./ReaderPreferencesProvider";
import "./reader.css";
import { segmentOutline, SEGMENTATION_LABELS } from "./segmentation";

type DocMeta = {
  id: string;
  title: string;
  status: string;
  originalSha256: string;
  formatId: string;
  currentRevisionId?: string;
};

const SHORTCUTS: [string, string][] = [
  ["/", "Find in document"],
  ["t", "Toggle table of contents"],
  ["1 / 2 / 3", "Theme Day / Sepia / Night"],
  ["o", "Original source view"],
  ["c", "Side-by-side comparison"],
  ["r", "Reading view"],
  ["[", "Previous find match"],
  ["]", "Next find match"],
  ["?", "Keyboard shortcut reference"],
];

export function ReaderShell({
  meta,
  doc,
  originalText,
  onDownloadOriginal,
  onReprocess,
}: {
  meta: DocMeta;
  doc: ClearDoc;
  originalText: string | null;
  onDownloadOriginal: () => void;
  onReprocess?: () => void;
}) {
  const { prefs, setPrefs, resetPrefs, resolvedTheme } = useReaderPreferences();
  const mainRef = useRef<HTMLDivElement>(null);
  const findRef = useRef<HTMLInputElement>(null);
  const shortcutsRef = useRef<HTMLDialogElement>(null);
  const [findOpen, setFindOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [currentHit, setCurrentHit] = useState(0);
  const [prefsOpen, setPrefsOpen] = useState(false);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const location = useLocation();
  const navigate = useNavigate();

  const outline = useMemo(() => segmentOutline(doc, prefs.segmentation), [doc, prefs.segmentation]);
  const hitCount = useMemo(() => countHits(doc, query, prefs), [doc, query, prefs]);

  // Deep link + scroll restoration
  useEffect(() => {
    const el = mainRef.current;
    if (!el) return;
    const hash = location.hash.replace(/^#/, "");
    if (hash) {
      const target = el.querySelector(`#${CSS.escape(hash)}`);
      target?.scrollIntoView({ block: "start" });
      return;
    }
    const key = SCROLL_KEY_PREFIX + meta.id;
    const saved = sessionStorage.getItem(key);
    if (saved) el.scrollTop = Number(saved) || 0;
  }, [location.hash, meta.id, doc, prefs.viewMode]);

  const onMainScroll = useCallback(() => {
    const el = mainRef.current;
    if (!el) return;
    const max = el.scrollHeight - el.clientHeight;
    setProgress(max > 0 ? Math.min(100, (el.scrollTop / max) * 100) : 0);
    sessionStorage.setItem(SCROLL_KEY_PREFIX + meta.id, String(el.scrollTop));

    const headings = [...el.querySelectorAll<HTMLElement>("h1[id],h2[id],h3[id],h4[id],h5[id],h6[id]")];
    let current: string | null = null;
    for (const h of headings) {
      if (h.offsetTop - el.scrollTop <= 80) current = h.id;
    }
    setActiveId(current);
  }, [meta.id]);

  useEffect(() => {
    const el = mainRef.current;
    if (!el) return;
    el.addEventListener("scroll", onMainScroll, { passive: true });
    onMainScroll();
    return () => el.removeEventListener("scroll", onMainScroll);
  }, [onMainScroll, prefs.viewMode]);

  const goHit = (dir: 1 | -1) => {
    if (hitCount <= 0) return;
    setCurrentHit((i) => (i + dir + hitCount) % hitCount);
  };

  useEffect(() => {
    if (currentHit < 0 || hitCount === 0) return;
    const mark = mainRef.current?.querySelector(`mark[data-hit="${currentHit}"]`);
    mark?.scrollIntoView({ block: "center" });
  }, [currentHit, hitCount, query]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      const typing = tag === "INPUT" || tag === "TEXTAREA" || (e.target as HTMLElement)?.isContentEditable;
      if (e.key === "?" && !typing) {
        e.preventDefault();
        shortcutsRef.current?.showModal();
        return;
      }
      if (e.key === "/" && !typing) {
        e.preventDefault();
        setFindOpen(true);
        queueMicrotask(() => findRef.current?.focus());
        return;
      }
      if (typing) return;
      if (e.key === "t") setPrefs({ tocOpen: !prefs.tocOpen });
      if (e.key === "1") setPrefs({ theme: "day" });
      if (e.key === "2") setPrefs({ theme: "sepia" });
      if (e.key === "3") setPrefs({ theme: "night" });
      if (e.key === "o") setPrefs({ viewMode: "original" });
      if (e.key === "c") setPrefs({ viewMode: "compare" });
      if (e.key === "r") setPrefs({ viewMode: "read" });
      if (e.key === "[" && findOpen) goHit(-1);
      if (e.key === "]" && findOpen) goHit(1);
      if (e.key === "Escape") {
        setFindOpen(false);
        shortcutsRef.current?.close();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [prefs.tocOpen, findOpen, setPrefs, hitCount]);

  const deepLink = (nodeId: string) => {
    navigate({ pathname: location.pathname, hash: nodeId }, { replace: false });
    const target = mainRef.current?.querySelector(`#${CSS.escape(nodeId)}`);
    target?.scrollIntoView({ block: "start" });
    setActiveId(nodeId);
  };

  const styleVars = {
    ["--reader-progress" as string]: `${progress}%`,
    ["--reader-font-body" as string]: fontFamilyCss(prefs.fontFamily),
    ["--reader-font-size" as string]: `${prefs.fontSizePx}px`,
    ["--reader-line-height" as string]: String(prefs.lineHeight),
    ["--reader-measure" as string]: `${prefs.measureCh}ch`,
    ["--reader-p-gap" as string]: `${prefs.paragraphSpacingEm}em`,
    ["--reader-h-gap" as string]: `${prefs.headingSpacingEm}em`,
    ["--reader-code-size" as string]: `${prefs.codeFontSizePx}px`,
  };

  return (
    <div className="reader-root" data-theme={resolvedTheme} style={styleVars}>
      <a className="skip-link" href="#reader-article">
        Skip to document
      </a>
      <div className="reader-progress" aria-hidden="true">
        <span />
      </div>

      <div className="reader-toolbar" role="toolbar" aria-label="Reader tools">
        <div className="group">
          <Link to="/" style={{ color: "var(--reader-accent)", textDecoration: "none", fontWeight: 600 }}>
            ← Library
          </Link>
          <span className="sep" />
          <strong style={{ fontFamily: "Fraunces, Georgia, serif" }}>{meta.title}</strong>
        </div>
        <div className="group">
          <button type="button" aria-pressed={prefs.tocOpen} onClick={() => setPrefs({ tocOpen: !prefs.tocOpen })}>
            Contents
          </button>
          <button type="button" aria-pressed={prefs.viewMode === "read"} onClick={() => setPrefs({ viewMode: "read" })}>
            Read
          </button>
          <button
            type="button"
            aria-pressed={prefs.viewMode === "original"}
            onClick={() => setPrefs({ viewMode: "original" })}
          >
            Original
          </button>
          <button
            type="button"
            aria-pressed={prefs.viewMode === "compare"}
            onClick={() => setPrefs({ viewMode: "compare" })}
          >
            Compare
          </button>
        </div>
        <span className="sep" />
        <div className="group" role="group" aria-label="Theme">
          {(["day", "sepia", "night", "system"] as const).map((t) => (
            <button key={t} type="button" aria-pressed={prefs.theme === t} onClick={() => setPrefs({ theme: t })}>
              {t[0]!.toUpperCase() + t.slice(1)}
            </button>
          ))}
        </div>
        <span className="sep" />
        <div className="group">
          <label>
            Structure{" "}
            <select
              value={prefs.segmentation}
              onChange={(e) => setPrefs({ segmentation: e.target.value as ReaderPreferences["segmentation"] })}
              aria-label="Segmentation detail"
            >
              {Object.entries(SEGMENTATION_LABELS).map(([k, label]) => (
                <option key={k} value={k}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <button type="button" aria-pressed={findOpen} onClick={() => setFindOpen((v) => !v)}>
            Find
          </button>
          <button type="button" aria-pressed={prefsOpen} onClick={() => setPrefsOpen((v) => !v)}>
            Preferences
          </button>
          <button type="button" onClick={() => shortcutsRef.current?.showModal()}>
            Shortcuts
          </button>
          <button type="button" onClick={onDownloadOriginal}>
            Download original
          </button>
          {onReprocess ? (
            <button type="button" onClick={onReprocess} title="Create a new derived revision">
              Reprocess
            </button>
          ) : null}
        </div>
      </div>

      {prefsOpen && (
        <div className="prefs-panel" aria-label="Reader preferences">
          <div className="row">
            <label htmlFor="fontFamily">Font</label>
            <select
              id="fontFamily"
              value={prefs.fontFamily}
              onChange={(e) => setPrefs({ fontFamily: e.target.value as ReaderPreferences["fontFamily"] })}
            >
              <option value="literata">Literata (serif)</option>
              <option value="source-sans">Source Sans 3</option>
              <option value="ibm-plex-sans">IBM Plex Sans</option>
              <option value="ibm-plex-mono">IBM Plex Mono</option>
            </select>
            <span />
          </div>
          <div className="row">
            <label htmlFor="fontSize">Font size</label>
            <input
              id="fontSize"
              type="range"
              min={14}
              max={28}
              value={prefs.fontSizePx}
              onChange={(e) => setPrefs({ fontSizePx: Number(e.target.value) })}
            />
            <span>{prefs.fontSizePx}px</span>
          </div>
          <div className="row">
            <label htmlFor="lineHeight">Line height</label>
            <input
              id="lineHeight"
              type="range"
              min={1.3}
              max={2.2}
              step={0.05}
              value={prefs.lineHeight}
              onChange={(e) => setPrefs({ lineHeight: Number(e.target.value) })}
            />
            <span>{prefs.lineHeight.toFixed(2)}</span>
          </div>
          <div className="row">
            <label htmlFor="measure">Reading width</label>
            <input
              id="measure"
              type="range"
              min={48}
              max={90}
              value={prefs.measureCh}
              onChange={(e) => setPrefs({ measureCh: Number(e.target.value) })}
            />
            <span>{prefs.measureCh}ch</span>
          </div>
          <div className="row">
            <label htmlFor="pGap">Paragraph spacing</label>
            <input
              id="pGap"
              type="range"
              min={0.4}
              max={1.8}
              step={0.05}
              value={prefs.paragraphSpacingEm}
              onChange={(e) => setPrefs({ paragraphSpacingEm: Number(e.target.value) })}
            />
            <span>{prefs.paragraphSpacingEm.toFixed(2)}em</span>
          </div>
          <div className="row">
            <label htmlFor="hGap">Heading spacing</label>
            <input
              id="hGap"
              type="range"
              min={0.8}
              max={2.4}
              step={0.05}
              value={prefs.headingSpacingEm}
              onChange={(e) => setPrefs({ headingSpacingEm: Number(e.target.value) })}
            />
            <span>{prefs.headingSpacingEm.toFixed(2)}em</span>
          </div>
          <div className="row">
            <label htmlFor="codeSize">Code font size</label>
            <input
              id="codeSize"
              type="range"
              min={11}
              max={18}
              value={prefs.codeFontSizePx}
              onChange={(e) => setPrefs({ codeFontSizePx: Number(e.target.value) })}
            />
            <span>{prefs.codeFontSizePx}px</span>
          </div>
          <div className="row">
            <label htmlFor="lineNums">Line numbers</label>
            <input
              id="lineNums"
              type="checkbox"
              checked={prefs.showLineNumbers}
              onChange={(e) => setPrefs({ showLineNumbers: e.target.checked })}
            />
            <span />
          </div>
          <div className="row">
            <label htmlFor="secCollapse">Section collapsing</label>
            <input
              id="secCollapse"
              type="checkbox"
              checked={prefs.sectionCollapsing}
              onChange={(e) => setPrefs({ sectionCollapsing: e.target.checked })}
            />
            <button type="button" onClick={resetPrefs}>
              Reset defaults
            </button>
          </div>
          <p style={{ margin: 0, color: "var(--reader-muted)", fontSize: "0.82rem" }}>
            Segmentation changes presentation only — originals and stored ClearDoc are never mutated.
          </p>
        </div>
      )}

      {findOpen && (
        <div className="reader-find" role="search">
          <label htmlFor="find-q">Find</label>
          <input
            id="find-q"
            ref={findRef}
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setCurrentHit(0);
            }}
            placeholder="Search in document"
          />
          <span>
            {hitCount === 0 ? "0" : `${currentHit + 1} / ${hitCount}`}
          </span>
          <button type="button" onClick={() => goHit(-1)} disabled={hitCount === 0}>
            Prev
          </button>
          <button type="button" onClick={() => goHit(1)} disabled={hitCount === 0}>
            Next
          </button>
          <button type="button" onClick={() => setFindOpen(false)}>
            Close
          </button>
        </div>
      )}

      <div className={`reader-body-grid${prefs.tocOpen ? "" : " toc-collapsed"}`}>
        <nav className="reader-toc" aria-label="Table of contents">
          <h2>Contents</h2>
          {outline.length === 0 ? (
            <p style={{ color: "var(--reader-muted)" }}>No headings</p>
          ) : (
            <ol>
              {outline.map((o) => (
                <li key={o.nodeId} style={{ marginLeft: `${(o.level - 1) * 0.55}rem` }}>
                  <a
                    href={`#${o.nodeId}`}
                    className={activeId === o.nodeId ? "active" : undefined}
                    aria-current={activeId === o.nodeId ? "true" : undefined}
                    onClick={(e) => {
                      e.preventDefault();
                      deepLink(o.nodeId);
                    }}
                  >
                    {o.text}
                  </a>
                </li>
              ))}
            </ol>
          )}
        </nav>

        <div className="reader-main" ref={mainRef} tabIndex={-1}>
          {prefs.viewMode === "read" && (
            <ClearDocView
              doc={doc}
              prefs={prefs}
              query={query}
              currentHit={currentHit}
              scrollParentRef={mainRef}
            />
          )}
          {prefs.viewMode === "original" && (
            <div className="original-only">
              <p className="pane-label">Original source (immutable)</p>
              <pre className="original-source">{originalText ?? "Loading original…"}</pre>
            </div>
          )}
          {prefs.viewMode === "compare" && (
            <div className="reader-compare">
              <div className="pane-original">
                <p className="pane-label">Original</p>
                <pre className="original-source">{originalText ?? "Loading original…"}</pre>
              </div>
              <div>
                <p className="pane-label">Derived ClearDoc (generated structure)</p>
                <ClearDocView doc={doc} prefs={prefs} query={query} currentHit={currentHit} scrollParentRef={mainRef} />
              </div>
            </div>
          )}
        </div>
      </div>

      <div className="reader-footer-bar">
        <span>
          {meta.formatId} · rev {meta.currentRevisionId?.slice(0, 8) || "—"} · sha256 {meta.originalSha256.slice(0, 12)}…
        </span>
        <span>
          Theme {resolvedTheme} · {SEGMENTATION_LABELS[prefs.segmentation]} · {Math.round(progress)}% read
        </span>
      </div>

      <dialog ref={shortcutsRef} className="shortcuts-dialog" aria-labelledby="shortcuts-title">
        <h2 id="shortcuts-title">Keyboard shortcuts</h2>
        <dl>
          {SHORTCUTS.map(([k, v]) => (
            <div key={k} style={{ display: "contents" }}>
              <dt>{k}</dt>
              <dd style={{ margin: 0 }}>{v}</dd>
            </div>
          ))}
        </dl>
        <p style={{ marginTop: "1rem" }}>
          <button type="button" onClick={() => shortcutsRef.current?.close()}>
            Close
          </button>
        </p>
      </dialog>
    </div>
  );
}
