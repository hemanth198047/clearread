/** Capability registry shapes used for local folder filtering (from GET /v1/capabilities). */

export type CapabilityFormat = {
  formatId: string;
  displayName?: string;
  extensions?: string[];
  mimeTypes?: string[];
  ingest?: string;
  notes?: string;
};

export type Capabilities = {
  registryVersion?: string;
  formats?: CapabilityFormat[];
};

/** Default upload / scan size limit (matches API clearread.limits.max-upload-bytes). */
export const DEFAULT_MAX_UPLOAD_BYTES = 10 * 1024 * 1024; // 10 MiB

/**
 * Client-side fallback when GET /v1/capabilities is unreachable or rate-limited.
 * Keeps local folder scanning usable offline; opening a file still needs the API.
 */
export const FALLBACK_CAPABILITY_FORMATS: CapabilityFormat[] = [
  {
    formatId: "markdown",
    displayName: "Markdown",
    extensions: [".md", ".markdown", ".mdown", ".mkd"],
    mimeTypes: ["text/markdown", "text/x-markdown"],
    ingest: "supported",
  },
  {
    formatId: "plain-text",
    displayName: "Plain text",
    extensions: [".txt", ".text", ".log"],
    mimeTypes: ["text/plain"],
    ingest: "supported",
  },
  {
    formatId: "json",
    displayName: "JSON",
    extensions: [".json", ".jsonl", ".ndjson"],
    mimeTypes: ["application/json", "application/x-ndjson"],
    ingest: "supported",
  },
  {
    formatId: "yaml",
    displayName: "YAML",
    extensions: [".yaml", ".yml"],
    mimeTypes: ["application/yaml", "text/yaml", "text/x-yaml"],
    ingest: "supported",
  },
  {
    formatId: "xml",
    displayName: "XML",
    extensions: [".xml", ".xsl", ".xsd"],
    mimeTypes: ["application/xml", "text/xml"],
    ingest: "supported",
  },
  {
    formatId: "csv",
    displayName: "CSV / TSV",
    extensions: [".csv", ".tsv"],
    mimeTypes: ["text/csv", "text/tab-separated-values"],
    ingest: "supported",
  },
  {
    formatId: "html",
    displayName: "HTML",
    extensions: [".html", ".htm"],
    mimeTypes: ["text/html"],
    ingest: "supported",
  },
  {
    formatId: "source-code",
    displayName: "Source / config",
    extensions: [
      ".ts",
      ".tsx",
      ".js",
      ".jsx",
      ".mjs",
      ".cjs",
      ".py",
      ".rb",
      ".go",
      ".rs",
      ".java",
      ".kt",
      ".cs",
      ".c",
      ".h",
      ".cpp",
      ".hpp",
      ".sh",
      ".bash",
      ".zsh",
      ".ps1",
      ".sql",
      ".toml",
      ".ini",
      ".cfg",
      ".conf",
      ".env",
      ".properties",
      ".gradle",
      ".dockerfile",
    ],
    mimeTypes: ["text/javascript", "application/javascript", "text/x-python", "text/x-java-source"],
    ingest: "supported",
  },
];

/** Resolve formats for filtering: prefer API, else fallback (never an empty index). */
export function formatsForScan(
  formats: CapabilityFormat[] | null | undefined
): { formats: CapabilityFormat[]; source: "api" | "fallback" } {
  if (formats && formats.length > 0) {
    return { formats, source: "api" };
  }
  return { formats: FALLBACK_CAPABILITY_FORMATS, source: "fallback" };
}

export type SupportedExtensionIndex = {
  /** lower-case extensions including leading dot, e.g. ".md" */
  extensions: Set<string>;
  /** lower-case mime types */
  mimeTypes: Set<string>;
  /** extension (with dot) → formatId for first matching supported format */
  extensionToFormatId: Map<string, string>;
};

/** Build lookup sets from capability formats where ingest === "supported". */
export function buildSupportedIndex(formats: CapabilityFormat[] | undefined | null): SupportedExtensionIndex {
  const extensions = new Set<string>();
  const mimeTypes = new Set<string>();
  const extensionToFormatId = new Map<string, string>();
  for (const f of formats ?? []) {
    if (f.ingest !== "supported") continue;
    for (const ext of f.extensions ?? []) {
      const normalized = normalizeExtension(ext);
      if (!normalized) continue;
      extensions.add(normalized);
      if (!extensionToFormatId.has(normalized)) {
        extensionToFormatId.set(normalized, f.formatId);
      }
    }
    for (const mime of f.mimeTypes ?? []) {
      const m = mime.trim().toLowerCase();
      if (m) mimeTypes.add(m);
    }
  }
  return { extensions, mimeTypes, extensionToFormatId };
}

export function normalizeExtension(extOrName: string): string | null {
  const raw = extOrName.trim().toLowerCase();
  if (!raw) return null;
  if (raw.includes("/") && !raw.startsWith(".")) {
    // likely a path — take basename extension
    const base = raw.split(/[/\\]/).pop() ?? raw;
    const dot = base.lastIndexOf(".");
    if (dot <= 0) return null;
    return base.slice(dot);
  }
  if (raw.startsWith(".")) return raw;
  if (!raw.includes(".")) return `.${raw}`;
  const base = raw.split(/[/\\]/).pop() ?? raw;
  const dot = base.lastIndexOf(".");
  if (dot <= 0) return null;
  return base.slice(dot);
}

export function extensionOf(fileName: string): string | null {
  return normalizeExtension(fileName);
}
