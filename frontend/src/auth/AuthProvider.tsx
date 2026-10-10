import { User, UserManager, WebStorageStateStore } from "oidc-client-ts";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

type AuthState = {
  user: User | null;
  loading: boolean;
  login: () => Promise<void>;
  logout: () => Promise<void>;
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

export function AuthProvider({ children }: { children: ReactNode }) {
  const manager = useMemo(() => (devBypass ? null : createManager()), []);
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(!devBypass);

  useEffect(() => {
    if (!manager) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        if (window.location.pathname === "/auth/callback") {
          const u = await manager.signinRedirectCallback();
          if (!cancelled) {
            setUser(u);
            window.history.replaceState({}, document.title, "/");
          }
        } else {
          const u = await manager.getUser();
          if (!cancelled) setUser(u);
        }
      } catch (err) {
        console.error(err);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [manager]);

  const login = useCallback(async () => {
    if (devBypass) return;
    await manager?.signinRedirect();
  }, [manager]);

  const logout = useCallback(async () => {
    if (devBypass) {
      setUser(null);
      return;
    }
    await manager?.signoutRedirect();
  }, [manager]);

  const getAccessToken = useCallback(() => {
    if (devBypass) {
      return sessionStorage.getItem("clearread.dev.token");
    }
    return user?.access_token ?? null;
  }, [user]);

  const value: AuthState = {
    user,
    loading,
    login,
    logout,
    getAccessToken,
    devBypass,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("AuthProvider missing");
  return ctx;
}
