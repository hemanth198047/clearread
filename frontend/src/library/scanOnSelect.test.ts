import { describe, expect, it } from "vitest";
import type { FolderScanSummary } from "./folderTypes";
import {
  errorFromScanSummaries,
  formatsReadyForScan,
  shouldScanImmediatelyAfterFolderSelect,
} from "./scanOnSelect";

describe("formatsReadyForScan", () => {
  it("treats null as still loading (must not scan yet)", () => {
    expect(formatsReadyForScan(null)).toBe(false);
  });

  it("treats empty or populated arrays as ready", () => {
    expect(formatsReadyForScan([])).toBe(true);
    expect(
      formatsReadyForScan([
        {
          formatId: "markdown",
          extensions: [".md"],
          mimeTypes: ["text/markdown"],
          ingest: "supported",
        },
      ])
    ).toBe(true);
  });
});

describe("shouldScanImmediatelyAfterFolderSelect", () => {
  it("always scans right after a successful folder pick (v1.2.1)", () => {
    expect(shouldScanImmediatelyAfterFolderSelect()).toBe(true);
  });
});

describe("errorFromScanSummaries", () => {
  const base = {
    folderId: "f1",
    scannedAt: "2026-10-14T00:00:00.000Z",
    matched: 0,
    skippedUnsupported: 0,
    skippedOversized: 0,
    skippedOther: 0,
  };

  it("prefers permission / missing-handle errors", () => {
    const summaries: FolderScanSummary[] = [
      { ...base, error: "Fallback folder: list is from the last picker session." },
      {
        ...base,
        folderId: "f2",
        error: "Permission denied — grant folder access when prompted, or re-add the folder in Settings.",
      },
    ];
    expect(errorFromScanSummaries(summaries)).toMatch(/Permission denied/);
  });

  it("returns null when every summary is clean", () => {
    expect(errorFromScanSummaries([{ ...base, matched: 3, error: null }])).toBeNull();
  });

  it("surfaces zero-match errors when no hard failure exists", () => {
    expect(
      errorFromScanSummaries([
        {
          ...base,
          error: "No readable ClearRead files found in this folder (check extensions and 10 MiB limit).",
        },
      ])
    ).toMatch(/No readable ClearRead files/);
  });
});
