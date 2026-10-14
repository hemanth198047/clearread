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
