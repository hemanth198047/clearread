import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { REMOTE_PREFS_DEBOUNCE_MS, ReaderPreferencesProvider, useReaderPreferences } from "./ReaderPreferencesProvider";
import { DEFAULT_PREFERENCES, LOCAL_PREFS_KEY, type ReaderPreferences } from "./preferences";

const authState = {
  user: { access_token: "tok", profile: { sub: "user-1" } } as {
    access_token?: string;
    profile?: { sub?: string };
  } | null,
  loading: false,
  authError: null as string | null,
  login: async () => undefined,
  register: async () => undefined,
  logout: async () => undefined,
  retryAuth: async () => undefined,
  getAccessToken: () => (authState.user ? "tok" : null) as string | null,
  devBypass: false,
};

vi.mock("../auth/AuthProvider", () => ({
  useAuth: () => ({
    ...authState,
    getAccessToken: () => authState.getAccessToken(),
  }),
}));

const fetchMock = vi.fn();

function PrefsProbe({ onPrefs }: { onPrefs?: (p: ReaderPreferences) => void }) {
  const { prefs, setPrefs, resolvedTheme } = useReaderPreferences();
  onPrefs?.(prefs);
  return (
    <div>
      <span data-testid="segmentation">{prefs.segmentation}</span>
      <span data-testid="theme">{prefs.theme}</span>
      <span data-testid="resolved">{resolvedTheme}</span>
      <button type="button" onClick={() => setPrefs({ segmentation: "detailed" })}>
        Set detailed
      </button>
      <button type="button" onClick={() => setPrefs({ theme: "sepia" })}>
        Set sepia
      </button>
    </div>
  );
}

describe("ReaderPreferencesProvider sync", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    authState.user = { access_token: "tok", profile: { sub: "user-1" } };
    authState.devBypass = false;
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal(
      "matchMedia",
      vi.fn().mockImplementation((query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
        dispatchEvent: vi.fn(),
      }))
    );
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("loads reader-preferences once and does not re-fire endlessly on re-render", async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      const path = String(url);
      if (path.includes("/v1/me/reader-preferences") && (!init?.method || init.method === "GET")) {
        return {
          ok: true,
          headers: { get: () => "application/json" },
          json: async () => ({ ...DEFAULT_PREFERENCES, theme: "sepia", segmentation: "balanced" }),
          arrayBuffer: async () => new ArrayBuffer(0),
        };
      }
      if (path.includes("/v1/me/reader-preferences") && init?.method === "PUT") {
        return {
          ok: true,
          headers: { get: () => "application/json" },
          json: async () => JSON.parse(String(init.body ?? "{}")),
          arrayBuffer: async () => new ArrayBuffer(0),
        };
      }
      return { ok: true, headers: { get: () => "application/json" }, json: async () => ({}), arrayBuffer: async () => new ArrayBuffer(0) };
    });

    const { rerender } = render(
      <ReaderPreferencesProvider>
        <PrefsProbe />
      </ReaderPreferencesProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId("segmentation").textContent).toBe("balanced");
    });

    const getsAfterHydrate = fetchMock.mock.calls.filter(
      ([url, init]) => String(url).includes("reader-preferences") && (!init?.method || init.method === "GET")
    ).length;
    expect(getsAfterHydrate).toBeGreaterThanOrEqual(1);
    expect(getsAfterHydrate).toBeLessThanOrEqual(2); // StrictMode may double-invoke once

    // Force many parent re-renders — must NOT spam GET again.
    for (let i = 0; i < 40; i++) {
      rerender(
        <ReaderPreferencesProvider>
          <PrefsProbe />
        </ReaderPreferencesProvider>
      );
    }

    await act(async () => {
      await Promise.resolve();
    });

    const getsAfterRerenders = fetchMock.mock.calls.filter(
      ([url, init]) => String(url).includes("reader-preferences") && (!init?.method || init.method === "GET")
    ).length;
    expect(getsAfterRerenders).toBe(getsAfterHydrate);
    expect(getsAfterRerenders).toBeLessThan(5);
  });

  it("saves remote prefs only on real user changes and debounces PUTs", async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      const path = String(url);
      if (path.includes("/v1/me/reader-preferences") && (!init?.method || init.method === "GET")) {
        return {
          ok: true,
          headers: { get: () => "application/json" },
          json: async () => ({ ...DEFAULT_PREFERENCES }),
          arrayBuffer: async () => new ArrayBuffer(0),
        };
      }
      if (path.includes("/v1/me/reader-preferences") && init?.method === "PUT") {
        return {
          ok: true,
          headers: { get: () => "application/json" },
          json: async () => JSON.parse(String(init.body ?? "{}")),
          arrayBuffer: async () => new ArrayBuffer(0),
        };
      }
      return { ok: true, headers: { get: () => "application/json" }, json: async () => ({}), arrayBuffer: async () => new ArrayBuffer(0) };
    });

    render(
      <ReaderPreferencesProvider>
        <PrefsProbe />
      </ReaderPreferencesProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId("theme").textContent).toBe("day");
    });

    const putsBefore = fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT").length;
    expect(putsBefore).toBe(0);

    await act(async () => {
      screen.getByRole("button", { name: "Set sepia" }).click();
      screen.getByRole("button", { name: "Set sepia" }).click();
      screen.getByRole("button", { name: "Set detailed" }).click();
    });

    // Still within debounce window — no PUT yet.
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT").length).toBe(0);

    await act(async () => {
      vi.advanceTimersByTime(REMOTE_PREFS_DEBOUNCE_MS + 50);
      await Promise.resolve();
    });

    const puts = fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT");
    expect(puts.length).toBe(1);
    const body = JSON.parse(String(puts[0]![1]!.body));
    expect(body.theme).toBe("sepia");
    expect(body.segmentation).toBe("detailed");
  });

  it("does not clobber Detailed with a late server hydrate after user change", async () => {
    let resolveGet!: (v: unknown) => void;
    const getPromise = new Promise((r) => {
      resolveGet = r;
    });

    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      const path = String(url);
      if (path.includes("/v1/me/reader-preferences") && (!init?.method || init.method === "GET")) {
        const remote = await getPromise;
        return {
          ok: true,
          headers: { get: () => "application/json" },
          json: async () => remote,
          arrayBuffer: async () => new ArrayBuffer(0),
        };
      }
      if (path.includes("/v1/me/reader-preferences") && init?.method === "PUT") {
        return {
          ok: true,
          headers: { get: () => "application/json" },
          json: async () => JSON.parse(String(init.body ?? "{}")),
          arrayBuffer: async () => new ArrayBuffer(0),
        };
      }
      return { ok: true, headers: { get: () => "application/json" }, json: async () => ({}), arrayBuffer: async () => new ArrayBuffer(0) };
    });

    localStorage.setItem(LOCAL_PREFS_KEY, JSON.stringify({ ...DEFAULT_PREFERENCES, segmentation: "conservative" }));

    render(
      <ReaderPreferencesProvider>
        <PrefsProbe />
      </ReaderPreferencesProvider>
    );

    expect(screen.getByTestId("segmentation").textContent).toBe("conservative");

    await act(async () => {
      screen.getByRole("button", { name: "Set detailed" }).click();
    });
    expect(screen.getByTestId("segmentation").textContent).toBe("detailed");

    await act(async () => {
      resolveGet({ ...DEFAULT_PREFERENCES, segmentation: "balanced", theme: "night" });
      await Promise.resolve();
      await Promise.resolve();
    });

    // User chose Detailed before hydrate finished — must stick.
    expect(screen.getByTestId("segmentation").textContent).toBe("detailed");
    expect(screen.getByTestId("theme").textContent).not.toBe("night");
  });
});
