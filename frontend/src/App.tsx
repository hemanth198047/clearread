import { useState } from "react";
import { Link, NavLink, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { useAuth } from "./auth/AuthProvider";
import { useBranding } from "./branding/BrandingProvider";
import { AdminPage } from "./pages/AdminPage";
import { AuthCallbackPage } from "./pages/AuthCallbackPage";
import { HomePage } from "./pages/HomePage";
import { InvitePage } from "./pages/InvitePage";
import { LibraryPage } from "./pages/LibraryPage";
import { PreviewPage } from "./pages/PreviewPage";
import { ReaderPage } from "./pages/ReaderPage";
import { ReaderPreferencesProvider } from "./reader/ReaderPreferencesProvider";

export function App() {
  const branding = useBranding();
  const auth = useAuth();
  const location = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);
  const inReader = location.pathname.startsWith("/documents/");
  const inPreview = location.pathname.startsWith("/preview/");
  const inAuthCallback = location.pathname === "/auth/callback";
  const chromeLess = inReader || inPreview;

  const crumb =
    location.pathname === "/"
      ? "Ingest"
      : location.pathname.startsWith("/library")
        ? "Library"
        : location.pathname.startsWith("/admin")
          ? "Admin"
          : location.pathname.startsWith("/invite")
            ? "Invite"
            : inAuthCallback
              ? "Sign-in"
              : "Workspace";

  return (
    <ReaderPreferencesProvider>
      <div
        className={`app-shell${chromeLess ? " app-shell-reader" : ""}${menuOpen ? " menu-open" : ""}`}
      >
        <a className="skip-link" href="#main">
          Skip to main content
        </a>

        {!chromeLess && (
          <header className="topbar topbar-dense">
            <div className="topbar-left">
              <button
                type="button"
                className="menu-btn"
                aria-label="Open menu"
                aria-expanded={menuOpen}
                onClick={() => setMenuOpen(true)}
              >
                ☰
              </button>
              <Link className="brand" to="/" onClick={() => setMenuOpen(false)}>
                {branding.productName}
                <span>.</span>
              </Link>
              <nav className="topbar-nav" aria-label="Primary">
                <NavLink to="/" end className={({ isActive }) => `topbar-nav-link${isActive ? " is-active" : ""}`}>
                  Ingest
                </NavLink>
                <NavLink
                  to="/library"
                  className={({ isActive }) => `topbar-nav-link${isActive ? " is-active" : ""}`}
                >
                  Library
                </NavLink>
                <NavLink
                  to="/admin"
                  className={({ isActive }) => `topbar-nav-link${isActive ? " is-active" : ""}`}
                >
                  Admin
                </NavLink>
              </nav>
            </div>
            <div className="topbar-actions">
              <span className="topbar-crumb muted">{crumb}</span>
              {auth.loading ? (
                <span className="muted">Checking session…</span>
              ) : auth.user || auth.devBypass ? (
                <>
                  <div className="topbar-user">
                    <span className="topbar-user-name">
                      {auth.user?.profile?.name ??
                        auth.user?.profile?.email ??
                        (auth.devBypass ? "Dev user" : "Signed in")}
                    </span>
                    <span className="topbar-user-meta">Signed in · same-origin /v1</span>
                  </div>
                  <Link className="button secondary" to="/library">
                    Open library
                  </Link>
                  <button type="button" className="secondary" onClick={() => void auth.logout()}>
                    Sign out
                  </button>
                </>
              ) : (
                <>
                  <button type="button" className="secondary" onClick={() => void auth.register()}>
                    Create account
                  </button>
                  <button type="button" onClick={() => void auth.login()}>
                    Sign in
                  </button>
                </>
              )}
            </div>
          </header>
        )}

        {!chromeLess && (
          <aside className={`mobile-drawer${menuOpen ? " open" : ""}`} aria-label="Workspace">
            <div className="mobile-drawer-head">
              <Link className="brand" to="/" onClick={() => setMenuOpen(false)}>
                {branding.productName}
                <span>.</span>
              </Link>
              <button
                type="button"
                className="menu-close"
                aria-label="Close menu"
                onClick={() => setMenuOpen(false)}
              >
                ✕
              </button>
            </div>
            <nav className="mobile-nav" aria-label="Mobile primary">
              <NavLink to="/" end onClick={() => setMenuOpen(false)}>
                Ingest
              </NavLink>
              <NavLink to="/library" onClick={() => setMenuOpen(false)}>
                Library
              </NavLink>
              <NavLink to="/admin" onClick={() => setMenuOpen(false)}>
                Admin
              </NavLink>
            </nav>
            <div className="mobile-meta">
              <div className="meta-row">
                <span className="meta-label">Session</span>
                <span className="meta-value">
                  {auth.loading
                    ? "Checking…"
                    : auth.user || auth.devBypass
                      ? "Signed in"
                      : "Guest"}
                </span>
              </div>
              <div className="meta-row">
                <span className="meta-label">API</span>
                <span className="meta-value mono">same-origin /v1</span>
              </div>
            </div>
          </aside>
        )}
        {!chromeLess && menuOpen && (
          <div className="sidebar-overlay" onClick={() => setMenuOpen(false)} />
        )}

        <div className={`content-wrapper${chromeLess ? " content-reader" : ""}`}>
          <main id="main" className={chromeLess ? "main-reader" : "main-app"}>
            <Routes>
              <Route path="/" element={<HomePage />} />
              <Route path="/library" element={<LibraryPage />} />
              <Route path="/admin" element={<AdminPage />} />
              <Route path="/invite/:token" element={<InvitePage />} />
              <Route path="/documents/:id" element={<ReaderPage />} />
              <Route path="/preview/:token" element={<PreviewPage />} />
              <Route path="/auth/callback" element={<AuthCallbackPage />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </main>

          {!chromeLess && (
            <footer className="footer">
              <span>Originals preserved · Exports are generated artifacts</span>
              <span className="muted">
                {branding.productName} · theme aligned to{" "}
                <a href="https://github.com/hemanth198047/Rebeautifier" rel="noreferrer" target="_blank">
                  Rebeautifier
                </a>
              </span>
            </footer>
          )}
        </div>
      </div>
    </ReaderPreferencesProvider>
  );
}
