import { Link, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { useAuth } from "./auth/AuthProvider";
import { useBranding } from "./branding/BrandingProvider";
import { HomePage } from "./pages/HomePage";
import { ReaderPage } from "./pages/ReaderPage";
import { ReaderPreferencesProvider } from "./reader/ReaderPreferencesProvider";

export function App() {
  const branding = useBranding();
  const auth = useAuth();
  const location = useLocation();
  const inReader = location.pathname.startsWith("/documents/");

  return (
    <ReaderPreferencesProvider>
      <div className={`app-shell${inReader ? " app-shell-reader" : ""}`}>
        <header className="topbar">
          <Link className="brand" to="/">
            <span>{branding.productName}</span>
          </Link>
          <div className="topbar-actions">
            {auth.loading ? (
              <span className="muted">Checking session…</span>
            ) : auth.user || auth.devBypass ? (
              <>
                <span className="muted">
                  {auth.user?.profile?.name ?? auth.user?.profile?.email ?? (auth.devBypass ? "Dev user" : "")}
                </span>
                <button type="button" className="secondary" onClick={() => void auth.logout()}>
                  Sign out
                </button>
              </>
            ) : (
              <button type="button" onClick={() => void auth.login()}>
                Sign in
              </button>
            )}
          </div>
        </header>
        <main className={inReader ? "main-reader" : undefined}>
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/documents/:id" element={<ReaderPage />} />
            <Route path="/auth/callback" element={<p className="muted">Completing sign-in…</p>} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </main>
        {!inReader && (
          <footer className="footer">
            Originals preserved. Structure is derived and marked when inferred. Temporary name configurable via branding.
          </footer>
        )}
      </div>
    </ReaderPreferencesProvider>
  );
}
