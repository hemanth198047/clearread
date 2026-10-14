import { Link } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";

/**
 * OIDC redirect landing. AuthProvider performs signinRedirectCallback on this path.
 * Never leave a blank "Completing sign-in…" forever — surface errors + retry.
 * Dense Rebeautifier-aligned chrome (slate/amber, Fraunces/Literata).
 */
export function AuthCallbackPage() {
  const auth = useAuth();

  if (auth.loading) {
    return (
      <section className="auth-stage" aria-busy="true" aria-live="polite">
        <div className="auth-stage-panel auth-callback">
          <p className="page-eyebrow">Authentication</p>
          <h1 className="auth-stage-brand">
            ClearRead<span>.</span>
          </h1>
          <h2>Completing sign-in</h2>
          <p className="lede">Exchanging the authorization code for a workspace session…</p>
          <div className="status-bar">
            <span className="status-dot busy" aria-hidden="true" />
            <span className="status-line">Signing in…</span>
          </div>
          <div className="auth-progress" aria-hidden="true">
            <span />
          </div>
        </div>
      </section>
    );
  }

  if (auth.authError) {
    return (
      <section className="auth-stage" role="alert">
        <div className="auth-stage-panel auth-callback">
          <p className="page-eyebrow">Authentication</p>
          <h1 className="auth-stage-brand">
            ClearRead<span>.</span>
          </h1>
          <h2>Sign-in could not complete</h2>
          <p className="status-line auth-error-line">{auth.authError}</p>
          <p className="muted">
            Common causes: redirect URI mismatch (must be <code>{window.location.origin}/auth/callback</code>), IdP
            down, expired login state, or clock skew. Clear site data for this origin if a prior attempt left a stale
            OIDC state.
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
        </div>
      </section>
    );
  }

  if (auth.user || auth.devBypass) {
    return (
      <section className="auth-stage">
        <div className="auth-stage-panel auth-callback">
          <p className="page-eyebrow">Welcome back</p>
          <h1 className="auth-stage-brand">
            ClearRead<span>.</span>
          </h1>
          <h2>Signed in</h2>
          <p className="lede">Redirecting to your workspace…</p>
          <div className="access-actions">
            <Link className="button" to="/">
              Continue to ingest
            </Link>
            <Link className="button secondary" to="/library">
              Open library
            </Link>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="auth-stage" role="status">
      <div className="auth-stage-panel auth-callback">
        <p className="page-eyebrow">Authentication</p>
        <h1 className="auth-stage-brand">
          ClearRead<span>.</span>
        </h1>
        <h2>No session yet</h2>
        <p className="lede">The callback finished without an error, but no user session was stored.</p>
        <div className="access-actions">
          <button type="button" onClick={() => void auth.login()}>
            Sign in
          </button>
          <Link className="button secondary" to="/">
            Home
          </Link>
        </div>
      </div>
    </section>
  );
}
