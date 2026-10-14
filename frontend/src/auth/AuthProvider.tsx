import { User, UserManager, WebStorageStateStore } from "oidc-client-ts";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

type AuthState = {
  user: User | null;
  loading: boolean;
  authError: string | null;
  login: () => Promise<void>;
  register: () => Promise<void>;
  logout: () => Promise<void>;
  retryAuth: () => Promise<void>;
  getAccessToken: () => string | null;
  devBypass: boolean;
};

const AuthContext = createContext<AuthState | null>(null);

const authority = import.meta.env.VITE_OIDC_AUTHORITY ?? "http://localhost:8081/realms/clearread";
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID ?? "clearread-spa";
const devBypass = import.meta.env.VITE_DEV_AUTH_BYPASS === "true";

function createManager() {
  return new UserManager({
    authority,
    client_id: clientId,
    redirect_uri: `${window.location.origin}/auth/callback`,
    post_logout_redirect_uri: window.location.origin,
    response_type: "code",
    scope: "openid profile email",
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: true,
  });
}

function formatOidcError(err: unknown): string {
  if (err && typeof err === "object" && "message" in err) {
    const msg = String((err as { message: string }).message);
    if (/No matching state|state.*not found/i.test(msg)) {
      return `${msg} — Start sign-in again from this browser tab (OIDC state was missing or expired).`;
    }
    if (/invalid_grant|expired/i.test(msg)) {
      return `${msg} — Authorization code expired; try Sign in again.`;
    }
    return msg;
  }
  return String(err);
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const manager = useMemo(() => (devBypass ? null : createManager()), []);
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(!devBypass);
  const [authError, setAuthError] = useState<string | null>(null);

  const finishCallbackRedirect = useCallback(() => {
    if (window.location.pathname === "/auth/callback") {
      const returnTo = sessionStorage.getItem("clearread.auth.returnTo") || "/";
      sessionStorage.removeItem("clearread.auth.returnTo");
      const safe = returnTo.startsWith("/") && !returnTo.startsWith("//") ? returnTo : "/";
      // Full navigation so React Router picks up the new path (replaceState alone leaves RR stale).
      window.location.replace(safe);
    }
  }, []);

  const bootstrap = useCallback(async () => {
    if (!manager) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setAuthError(null);
    try {
      if (window.location.pathname === "/auth/callback") {
        const u = await manager.signinRedirectCallback();
        setUser(u);
        finishCallbackRedirect();
      } else {
        const u = await manager.getUser();
        setUser(u && !u.expired ? u : null);
      }
    } catch (err) {
      console.error(err);
      setAuthError(formatOidcError(err));
      setUser(null);
      // Stay on /auth/callback so AuthCallbackPage can show the error (do not blank-redirect).
    } finally {
      setLoading(false);
    }
  }, [manager, finishCallbackRedirect]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (cancelled) return;
      await bootstrap();
    })();
    return () => {
      cancelled = true;
    };
  }, [bootstrap]);

  const login = useCallback(async () => {
    if (devBypass) return;
    setAuthError(null);
    const path = window.location.pathname + window.location.search + window.location.hash;
    if (path && path !== "/auth/callback") {
      sessionStorage.setItem("clearread.auth.returnTo", path);
    }
    await manager?.signinRedirect();
  }, [manager]);

  /** Opens Keycloak self-registration (realm registrationAllowed) via OIDC PKCE. */
  const register = useCallback(async () => {
    if (devBypass || !manager) return;
    setAuthError(null);
    const path = window.location.pathname + window.location.search + window.location.hash;
    if (path && path !== "/auth/callback") {
      sessionStorage.setItem("clearread.auth.returnTo", path);
    }
    const meta = await manager.metadataService.getMetadata();
    const previous = meta.authorization_endpoint;
    meta.authorization_endpoint = `${authority.replace(/\/$/, "")}/protocol/openid-connect/registrations`;
    try {
      await manager.signinRedirect();
    } finally {
      meta.authorization_endpoint = previous;
    }
  }, [manager]);

  const logout = useCallback(async () => {
    if (devBypass) {
      setUser(null);
      return;
    }
    await manager?.signoutRedirect();
  }, [manager]);

  const retryAuth = useCallback(async () => {
    setAuthError(null);
    if (window.location.pathname === "/auth/callback") {
      window.history.replaceState({}, document.title, "/");
    }
    await login();
  }, [login]);

  const getAccessToken = useCallback(() => {
    if (devBypass) {
      return sessionStorage.getItem("clearread.dev.token");
    }
    if (!user || user.expired) return null;
    return user.access_token ?? null;
  }, [user]);

  const value = useMemo<AuthState>(
    () => ({
      user,
      loading,
      authError,
      login,
      register,
      logout,
      retryAuth,
      getAccessToken,
      devBypass,
    }),
    [user, loading, authError, login, register, logout, retryAuth, getAccessToken]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("AuthProvider missing");
  return ctx;
}
