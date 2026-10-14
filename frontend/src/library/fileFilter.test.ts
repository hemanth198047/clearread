import { describe, expect, it } from "vitest";
import {
  buildSupportedIndex,
  DEFAULT_MAX_UPLOAD_BYTES,
  FALLBACK_CAPABILITY_FORMATS,
  formatsForScan,
  normalizeExtension,
} from "./capabilities";
import { filterLocalFile, isReadableLocalFile, shouldSkipDirectoryName } from "./fileFilter";

const sampleFormats = [
  {
    formatId: "markdown",
    extensions: [".md", ".markdown"],
    mimeTypes: ["text/markdown"],
    ingest: "supported",
  },
  {
    formatId: "json",
    extensions: [".json"],
    mimeTypes: ["application/json"],
    ingest: "supported",
  },
  {
    formatId: "csv",
    extensions: [".csv"],
    mimeTypes: ["text/csv"],
    ingest: "supported",
  },
  {
    formatId: "source-code",
    extensions: [".ts", ".py"],
    mimeTypes: ["text/javascript"],
    ingest: "supported",
  },
  {
    formatId: "pdf",
    extensions: [".pdf"],
    mimeTypes: ["application/pdf"],
    ingest: "unsupported",
  },
];

describe("normalizeExtension", () => {
  it("normalizes bare and dotted extensions", () => {
    expect(normalizeExtension(".MD")).toBe(".md");
    expect(normalizeExtension("md")).toBe(".md");
    expect(normalizeExtension("notes/README.Markdown")).toBe(".markdown");
  });
});

describe("buildSupportedIndex", () => {
  it("indexes only ingest=supported formats", () => {
    const index = buildSupportedIndex(sampleFormats);
    expect(index.extensions.has(".md")).toBe(true);
    expect(index.extensions.has(".pdf")).toBe(false);
    expect(index.extensionToFormatId.get(".json")).toBe("json");
    expect(index.mimeTypes.has("text/csv")).toBe(true);
  });
});

describe("filterLocalFile", () => {
  const index = buildSupportedIndex(sampleFormats);

  it("accepts supported extensions under the size limit", () => {
    const r = filterLocalFile({ name: "readme.md", size: 1200 }, { index });
    expect(r.ok).toBe(true);
    expect(r.formatId).toBe("markdown");
    expect(isReadableLocalFile({ name: "data.CSV", size: 10 }, { index })).toBe(true);
  });

  it("rejects unsupported binaries and unknown extensions", () => {
    expect(filterLocalFile({ name: "photo.png", size: 100 }, { index }).reason).toBe("unsupported_extension");
    expect(filterLocalFile({ name: "deck.pdf", size: 100 }, { index }).reason).toBe("unsupported_extension");
  });

  it("rejects oversized files using the 10 MiB default", () => {
    const r = filterLocalFile({ name: "big.md", size: DEFAULT_MAX_UPLOAD_BYTES + 1 }, { index });
    expect(r.ok).toBe(false);
    expect(r.reason).toBe("oversized");
  });

  it("accepts MIME match when extension is missing but type is known", () => {
    const r = filterLocalFile({ name: "untitled", size: 40, type: "application/json" }, { index });
    expect(r.ok).toBe(true);
  });

  it("skips hidden and empty files", () => {
    expect(filterLocalFile({ name: ".env", size: 10 }, { index }).reason).toBe("hidden");
    expect(filterLocalFile({ name: "empty.md", size: 0 }, { index }).reason).toBe("empty");
  });

  it("respects custom maxBytes", () => {
    const r = filterLocalFile({ name: "a.md", size: 50 }, { index, maxBytes: 40 });
    expect(r.reason).toBe("oversized");
  });
});

describe("shouldSkipDirectoryName", () => {
  it("skips VCS, node_modules, and dotdirs", () => {
    expect(shouldSkipDirectoryName("node_modules")).toBe(true);
    expect(shouldSkipDirectoryName(".git")).toBe(true);
    expect(shouldSkipDirectoryName("src")).toBe(false);
  });
});

describe("formatsForScan fallback", () => {
  it("uses API formats when present", () => {
    const { formats, source } = formatsForScan(sampleFormats);
    expect(source).toBe("api");
    expect(formats).toBe(sampleFormats);
  });

  it("falls back when capabilities are missing or empty (API down)", () => {
    expect(formatsForScan(null).source).toBe("fallback");
    expect(formatsForScan([]).source).toBe("fallback");
    const index = buildSupportedIndex(formatsForScan(null).formats);
    expect(index.extensions.has(".md")).toBe(true);
    expect(filterLocalFile({ name: "note.md", size: 12 }, { index }).ok).toBe(true);
    expect(FALLBACK_CAPABILITY_FORMATS.length).toBeGreaterThan(0);
  });
});
