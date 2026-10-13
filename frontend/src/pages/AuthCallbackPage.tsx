import { Link } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";

/**
 * OIDC redirect landing. AuthProvider performs signinRedirectCallback on this path.
 * Never leave a blank "Completing sign-in…" forever — surface errors + retry.
 */
export function AuthCallbackPage() {
  const auth = useAuth();

  if (auth.loading) {
    return (
      <section className="panel auth-callback" aria-busy="true" aria-live="polite">
        <p className="page-eyebrow">Authentication</p>
        <h1>Completing sign-in</h1>
        <p className="muted">Exchanging authorization code for a session…</p>
        <div className="status-bar">
          <span className="status-dot busy" aria-hidden="true" />
          <span className="status-line">Signing in…</span>
        </div>
      </section>
    );
  }

  if (auth.authError) {
    return (
      <section className="panel auth-callback" role="alert">
        <p className="page-eyebrow">Authentication</p>
        <h1>Sign-in could not complete</h1>
        <p className="status-line">{auth.authError}</p>
        <p className="muted">
          Common causes: redirect URI mismatch (must be <code>{window.location.origin}/auth/callback</code>), IdP down,
          expired login state, or clock skew. Clear site data for this origin if a prior attempt left a stale OIDC state.
        </p>
        <div className="access-actions">
          <button type="button" onClick={() => void auth.retryAuth()}>
            Retry sign-in
          </button>
          <button type="button" className="secondary" onClick={() => void auth.register()}>
            Create account
          </button>
          <Link className="button secondary" to="/">
            Back home
          </Link>
        </div>
      </section>
    );
  }

  if (auth.user || auth.devBypass) {
    return (
      <section className="panel auth-callback">
        <h1>Signed in</h1>
        <p className="muted">Redirecting to your workspace…</p>
        <div className="access-actions">
          <Link className="button" to="/">
            Continue to ingest
          </Link>
          <Link className="button secondary" to="/library">
            Open library
          </Link>
        </div>
      </section>
    );
  }

  return (
    <section className="panel auth-callback" role="status">
      <h1>No session yet</h1>
      <p className="muted">The callback finished without an error, but no user session was stored.</p>
      <div className="access-actions">
        <button type="button" onClick={() => void auth.login()}>
          Sign in
        </button>
        <Link className="button secondary" to="/">
          Home
        </Link>
      </div>
    </section>
  );
}
