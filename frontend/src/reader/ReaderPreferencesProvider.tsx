import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useEffectEvent,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { apiFetch } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import {
  DEFAULT_PREFERENCES,
  loadLocalPreferences,
  mergePreferences,
  saveLocalPreferences,
  type ReaderPreferences,
} from "./preferences";
import { applyThemeTokens, resolveTheme, type ReaderThemeId } from "./themes";

type Ctx = {
  prefs: ReaderPreferences;
  setPrefs: (patch: Partial<ReaderPreferences>) => void;
  resetPrefs: () => void;
  resolvedTheme: "day" | "sepia" | "night";
};

const ReaderPreferencesContext = createContext<Ctx | null>(null);

export function ReaderPreferencesProvider({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const [prefs, setPrefsState] = useState<ReaderPreferences>(() => loadLocalPreferences());
  const [prefersDark, setPrefersDark] = useState(
    () => typeof window !== "undefined" && window.matchMedia("(prefers-color-scheme: dark)").matches
  );

  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => setPrefersDark(mq.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  const syncFromServer = useEffectEvent(async (token: string) => {
    try {
      const remote = (await apiFetch("/v1/me/reader-preferences", token)) as Partial<ReaderPreferences>;
      if (remote && typeof remote === "object" && Object.keys(remote).length > 0) {
        const merged = mergePreferences({ ...loadLocalPreferences(), ...remote });
        setPrefsState(merged);
        saveLocalPreferences(merged);
      }
    } catch {
      /* keep local */
    }
  });

  useEffect(() => {
    const token = auth.getAccessToken();
    if (!token || (!auth.user && !auth.devBypass)) return;
    void syncFromServer(token);
  }, [auth.user, auth.devBypass, auth, syncFromServer]);

  const resolvedTheme = resolveTheme(prefs.theme, prefersDark);

  useEffect(() => {
    applyThemeTokens(document.documentElement, resolvedTheme);
    document.documentElement.style.colorScheme = resolvedTheme === "night" ? "dark" : "light";
  }, [resolvedTheme]);

  const persistRemote = useEffectEvent(async (next: ReaderPreferences) => {
    const token = auth.getAccessToken();
    if (!token || (!auth.user && !auth.devBypass)) return;
    try {
      await apiFetch("/v1/me/reader-preferences", token, {
        method: "PUT",
        body: JSON.stringify(next),
      });
    } catch {
      /* local still saved */
    }
  });

  const setPrefs = useCallback(
    (patch: Partial<ReaderPreferences>) => {
      setPrefsState((prev) => {
        const next = { ...prev, ...patch };
        saveLocalPreferences(next);
        void persistRemote(next);
        return next;
      });
    },
    [persistRemote]
  );

  const resetPrefs = useCallback(() => {
    setPrefsState(DEFAULT_PREFERENCES);
    saveLocalPreferences(DEFAULT_PREFERENCES);
    void persistRemote(DEFAULT_PREFERENCES);
  }, [persistRemote]);

  const value = useMemo(
    () => ({ prefs, setPrefs, resetPrefs, resolvedTheme }),
    [prefs, setPrefs, resetPrefs, resolvedTheme]
  );

  return <ReaderPreferencesContext.Provider value={value}>{children}</ReaderPreferencesContext.Provider>;
}

export function useReaderPreferences() {
  const ctx = useContext(ReaderPreferencesContext);
  if (!ctx) throw new Error("ReaderPreferencesProvider missing");
  return ctx;
}

export function setThemeQuick(setPrefs: Ctx["setPrefs"], theme: ReaderThemeId) {
  setPrefs({ theme });
}
