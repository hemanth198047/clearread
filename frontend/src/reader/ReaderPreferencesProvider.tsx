import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useEffectEvent,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { apiFetch } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import {
  DEFAULT_PREFERENCES,
  loadLocalPreferences,
  mergePreferences,
  preferencesEqual,
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

/** Debounce remote PUTs so slider drags / rapid toggles do not flood the API. */
export const REMOTE_PREFS_DEBOUNCE_MS = 400;

function authSyncKey(user: { access_token?: string; profile?: { sub?: string } } | null, devBypass: boolean): string {
  if (devBypass) return "dev-bypass";
  if (!user) return "";
  return String(user.profile?.sub ?? user.access_token ?? "signed-in");
}

export function ReaderPreferencesProvider({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const [prefs, setPrefsState] = useState<ReaderPreferences>(() => loadLocalPreferences());
  const [prefersDark, setPrefersDark] = useState(
    () => typeof window !== "undefined" && window.matchMedia("(prefers-color-scheme: dark)").matches
  );
  const lastPersistedJson = useRef<string>(JSON.stringify(loadLocalPreferences()));
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** Prevents re-hydrating the same auth session (load once per user). */
  const hydratedForKey = useRef<string>("");
  /** Once the user edits prefs, do not clobber with a late server hydrate (Detailed race). */
  const userTouched = useRef(false);

  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => setPrefersDark(mq.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  const syncFromServer = useEffectEvent(async (token: string) => {
    try {
      const remote = (await apiFetch("/v1/me/reader-preferences", token)) as Partial<ReaderPreferences>;
      if (userTouched.current) return;
      if (remote && typeof remote === "object" && Object.keys(remote).length > 0) {
        const merged = mergePreferences({ ...loadLocalPreferences(), ...remote });
        setPrefsState((prev) => {
          if (userTouched.current || preferencesEqual(prev, merged)) return prev;
          saveLocalPreferences(merged);
          lastPersistedJson.current = JSON.stringify(merged);
          return merged;
        });
      }
    } catch {
      /* keep local */
    }
  });

  const syncKey = authSyncKey(auth.user, auth.devBypass);

  // Load remote prefs once per signed-in session. Do NOT list useEffectEvent fns in deps —
  // their identity changes every render and would re-fire GET forever.
  useEffect(() => {
    if (!syncKey) return;
    if (hydratedForKey.current === syncKey) return;
    const token = auth.getAccessToken();
    if (!token) return;
    hydratedForKey.current = syncKey;
    void syncFromServer(token);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- syncFromServer is an Effect Event; auth.getAccessToken reads latest via closure
  }, [syncKey]);

  const resolvedTheme = resolveTheme(prefs.theme, prefersDark);

  useEffect(() => {
    applyThemeTokens(document.documentElement, resolvedTheme);
    document.documentElement.style.colorScheme = resolvedTheme === "night" ? "dark" : "light";
  }, [resolvedTheme]);

  const persistRemote = useEffectEvent(async (next: ReaderPreferences) => {
    const token = auth.getAccessToken();
    if (!token || (!auth.user && !auth.devBypass)) return;
    const json = JSON.stringify(next);
    if (json === lastPersistedJson.current) return;
    try {
      await apiFetch("/v1/me/reader-preferences", token, {
        method: "PUT",
        body: json,
      });
      lastPersistedJson.current = json;
    } catch {
      /* local still saved */
    }
  });

  const schedulePersistRemote = useEffectEvent((next: ReaderPreferences) => {
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => {
      saveTimer.current = null;
      void persistRemote(next);
    }, REMOTE_PREFS_DEBOUNCE_MS);
  });

  useEffect(() => {
    return () => {
      if (saveTimer.current) clearTimeout(saveTimer.current);
    };
  }, []);

  const prefsRef = useRef(prefs);
  prefsRef.current = prefs;

  const setPrefs = useCallback((patch: Partial<ReaderPreferences>) => {
    userTouched.current = true;
    const next = mergePreferences({ ...prefsRef.current, ...patch });
    if (preferencesEqual(prefsRef.current, next)) return;
    prefsRef.current = next;
    setPrefsState(next);
    saveLocalPreferences(next);
    // Call Effect Events from the event handler — not inside a setState updater
    // (React 19 forbids useEffectEvent during render / updater execution).
    schedulePersistRemote(next);
  }, []);

  const resetPrefs = useCallback(() => {
    userTouched.current = true;
    prefsRef.current = DEFAULT_PREFERENCES;
    setPrefsState(DEFAULT_PREFERENCES);
    saveLocalPreferences(DEFAULT_PREFERENCES);
    schedulePersistRemote(DEFAULT_PREFERENCES);
  }, []);

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
