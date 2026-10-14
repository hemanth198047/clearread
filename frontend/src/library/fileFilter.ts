import {
  DEFAULT_MAX_UPLOAD_BYTES,
  buildSupportedIndex,
  extensionOf,
  type CapabilityFormat,
  type SupportedExtensionIndex,
} from "./capabilities";

export type FileFilterInput = {
  name: string;
  size: number;
  type?: string;
  /** Relative path within a folder (optional; used only for display). */
  relativePath?: string;
};

export type FileFilterReason =
  | "ok"
  | "unsupported_extension"
  | "oversized"
  | "empty"
  | "hidden";

export type FileFilterResult = {
  ok: boolean;
  reason: FileFilterReason;
  formatId?: string;
  extension?: string | null;
};

export type FileFilterOptions = {
  maxBytes?: number;
  /** Skip dotfiles (name starts with ".") — default true. */
  skipHidden?: boolean;
  index?: SupportedExtensionIndex;
  formats?: CapabilityFormat[] | null;
};

/**
 * Decide whether a local file should appear in the folder library list.
 * List-only: does not read file contents. Unsupported binaries are rejected by extension/MIME.
 */
export function filterLocalFile(file: FileFilterInput, options: FileFilterOptions = {}): FileFilterResult {
  const maxBytes = options.maxBytes ?? DEFAULT_MAX_UPLOAD_BYTES;
  const skipHidden = options.skipHidden !== false;
  const index = options.index ?? buildSupportedIndex(options.formats);

  const baseName = (file.name.split(/[/\\]/).pop() ?? file.name).trim();
  if (!baseName) {
    return { ok: false, reason: "empty" };
  }
  if (skipHidden && baseName.startsWith(".")) {
    return { ok: false, reason: "hidden" };
  }
  if (file.size <= 0) {
    return { ok: false, reason: "empty" };
  }
  if (file.size > maxBytes) {
    return { ok: false, reason: "oversized", extension: extensionOf(baseName) };
  }

  const ext = extensionOf(baseName);
  const mime = (file.type ?? "").trim().toLowerCase();

  let formatId: string | undefined;
  if (ext && index.extensions.has(ext)) {
    formatId = index.extensionToFormatId.get(ext);
  } else if (mime && index.mimeTypes.has(mime)) {
    // MIME match without known extension — still accept when registry lists the MIME.
    formatId = undefined;
  } else {
    return { ok: false, reason: "unsupported_extension", extension: ext };
  }

  return { ok: true, reason: "ok", formatId, extension: ext };
}

/** True when the file should be listed for ClearRead ingest. */
export function isReadableLocalFile(file: FileFilterInput, options: FileFilterOptions = {}): boolean {
  return filterLocalFile(file, options).ok;
}

/**
 * Directory / path segments to skip while walking a preferred folder.
 * Keeps scans practical; never auto-ingests — only listing.
 */
export const SKIP_DIR_NAMES = new Set([
  "node_modules",
  ".git",
  ".svn",
  ".hg",
  ".idea",
  ".vscode",
  "dist",
  "build",
  "out",
  "coverage",
  "__pycache__",
  ".next",
  ".turbo",
  "vendor",
]);

export function shouldSkipDirectoryName(name: string): boolean {
  const n = name.trim();
  if (!n) return true;
  if (n === "." || n === "..") return true;
  if (n.startsWith(".")) return true;
  return SKIP_DIR_NAMES.has(n.toLowerCase());
}
