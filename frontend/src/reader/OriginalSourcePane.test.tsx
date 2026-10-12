import { render, screen, fireEvent, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ReaderShell } from "./ReaderShell";
import { DEFAULT_PREFERENCES, SCROLL_KEY_PREFIX, type ReaderPreferences } from "./preferences";

const prefsState: { prefs: ReaderPreferences } = {
  prefs: { ...DEFAULT_PREFERENCES },
};

vi.mock("./ReaderPreferencesProvider", () => ({
  useReaderPreferences: () => ({
    prefs: prefsState.prefs,
    setPrefs: (patch: Partial<ReaderPreferences>) => {
      prefsState.prefs = { ...prefsState.prefs, ...patch };
    },
    resetPrefs: () => {
      prefsState.prefs = { ...DEFAULT_PREFERENCES };
    },
    resolvedTheme: "day" as const,
  }),
}));

const sampleDoc = {
  metadata: { title: "Sample" },
  outline: [{ level: 1, text: "Sample", nodeId: "n_1" }],
  warnings: [],
  body: [
    {
      type: "heading" as const,
      level: 1,
      nodeId: "n_1",
      children: [{ type: "text" as const, value: "Sample" }],
    },
    {
      type: "paragraph" as const,
      nodeId: "n_2",
      children: [{ type: "text" as const, value: "Hello derived body" }],
    },
  ],
};

const meta = {
  id: "doc-original-test",
  title: "Sample",
  status: "ready",
  originalSha256: "abc123def456",
  formatId: "markdown",
  currentRevisionId: "rev-1",
};

const originalMd = "# Sample\n\nImmutable original bytes.\n";

describe("Original source pane", () => {
  beforeEach(() => {
    prefsState.prefs = { ...DEFAULT_PREFERENCES, viewMode: "read" };
    sessionStorage.clear();
  });

  it("shows immutable original markdown when Original is selected", () => {
    prefsState.prefs = { ...DEFAULT_PREFERENCES, viewMode: "original" };
    render(
      <MemoryRouter>
        <ReaderShell
          meta={meta}
          doc={sampleDoc}
          originalText={originalMd}
          onDownloadOriginal={() => undefined}
        />
      </MemoryRouter>
    );
    expect(screen.getByText("Original source (immutable)")).toBeTruthy();
    expect(screen.getByText(/Immutable original bytes/)).toBeTruthy();
    expect(screen.queryByText("Hello derived body")).toBeNull();
  });

  it("shows loading and error empty states instead of a blank pane", () => {
    prefsState.prefs = { ...DEFAULT_PREFERENCES, viewMode: "original" };
    const { rerender } = render(
      <MemoryRouter>
        <ReaderShell
          meta={meta}
          doc={sampleDoc}
          originalText={null}
          originalLoading
          onDownloadOriginal={() => undefined}
        />
      </MemoryRouter>
    );
    expect(screen.getByText("Loading original…")).toBeTruthy();

    rerender(
      <MemoryRouter>
        <ReaderShell
          meta={meta}
          doc={sampleDoc}
          originalText={null}
          originalError="Original not available"
          onDownloadOriginal={() => undefined}
        />
      </MemoryRouter>
    );
    expect(screen.getByRole("alert").textContent).toMatch(/Original not available/);
  });

  it("does not apply read-mode scroll offset when switching to Original", () => {
    sessionStorage.setItem(`${SCROLL_KEY_PREFIX}${meta.id}.read`, "2400");
    prefsState.prefs = { ...DEFAULT_PREFERENCES, viewMode: "original" };
    const { container } = render(
      <MemoryRouter>
        <ReaderShell
          meta={meta}
          doc={sampleDoc}
          originalText={originalMd}
          onDownloadOriginal={() => undefined}
        />
      </MemoryRouter>
    );
    const main = container.querySelector(".reader-main") as HTMLDivElement;
    expect(main).toBeTruthy();
    expect(main.scrollTop).toBe(0);
    expect(within(main).getByText(/Immutable original bytes/)).toBeTruthy();
  });

  it("Original toolbar control sets original view mode", () => {
    const { rerender } = render(
      <MemoryRouter>
        <ReaderShell
          meta={meta}
          doc={sampleDoc}
          originalText={originalMd}
          onDownloadOriginal={() => undefined}
        />
      </MemoryRouter>
    );
    fireEvent.click(screen.getByRole("button", { name: "Original" }));
    expect(prefsState.prefs.viewMode).toBe("original");
    prefsState.prefs = { ...prefsState.prefs, viewMode: "original" };
    rerender(
      <MemoryRouter>
        <ReaderShell
          meta={meta}
          doc={sampleDoc}
          originalText={originalMd}
          onDownloadOriginal={() => undefined}
        />
      </MemoryRouter>
    );
    expect(screen.getByText(/Immutable original bytes/)).toBeTruthy();
  });
});
