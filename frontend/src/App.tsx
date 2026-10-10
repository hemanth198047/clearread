import { Link, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { useAuth } from "./auth/AuthProvider";
import { useBranding } from "./branding/BrandingProvider";
import { AdminPage } from "./pages/AdminPage";
import { HomePage } from "./pages/HomePage";
import { LibraryPage } from "./pages/LibraryPage";
import { PreviewPage } from "./pages/PreviewPage";
import { ReaderPage } from "./pages/ReaderPage";
import { ReaderPreferencesProvider } from "./reader/ReaderPreferencesProvider";

export function App() {
  const branding = useBranding();
  const auth = useAuth();
  const location = useLocation();
  const inReader = location.pathname.startsWith("/documents/");
  const inPreview = location.pathname.startsWith("/preview/");

  return (
    <ReaderPreferencesProvider>
      <div className={`app-shell${inReader ? " app-shell-reader" : ""}`}>
        <header className="topbar">
          <Link className="brand" to="/">
            <span>{branding.productName}</span>
          </Link>
          <nav className="topbar-nav" aria-label="Primary">
            <Link to="/">Ingest</Link>
            <Link to="/library">Library</Link>
            <Link to="/admin">Admin</Link>
          </nav>
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
        <main className={inReader || inPreview ? "main-reader" : undefined}>
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/library" element={<LibraryPage />} />
            <Route path="/admin" element={<AdminPage />} />
            <Route path="/documents/:id" element={<ReaderPage />} />
            <Route path="/preview/:token" element={<PreviewPage />} />
            <Route path="/auth/callback" element={<p className="muted">Completing sign-in…</p>} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </main>
        {!inReader && !inPreview && (
          <footer className="footer">
            Originals preserved. Exports are generated artifacts. Temporary name configurable via branding.
          </footer>
        )}
      </div>
    </ReaderPreferencesProvider>
  );
}
