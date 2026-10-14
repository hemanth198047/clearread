import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiFetch, formatApiError } from "../api/client";
import { useAuth } from "../auth/AuthProvider";

type InviteInfo = {
  email: string;
  role: string;
  status: string;
  tenantSlug: string;
  tenantName: string;
  expiresAt: string;
  instructions: string;
};

export function InvitePage() {
  const { token } = useParams();
  const auth = useAuth();
  const [invite, setInvite] = useState<InviteInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    apiFetch(`/v1/invites/${token}`, null)
      .then((data) => setInvite(data as InviteInfo))
      .catch((e) => setError(formatApiError(e, "Invite")));
  }, [token]);

  return (
    <section className="auth-stage">
      <div className="auth-stage-panel access-panel">
        <p className="page-eyebrow">Invite</p>
        <h1 className="auth-stage-brand">
          ClearRead<span>.</span>
        </h1>
        <h2>Workspace invite</h2>
        <p className="lede">Accept an invite by creating an account or signing in with the invited email.</p>
      {error && (
        <p role="alert" className="alert">
          {error}
        </p>
      )}
      {!error && !invite && <p className="muted">Loading invite…</p>}
      {invite && (
        <>
          <p>
            You are invited to <strong>{invite.tenantName}</strong> ({invite.tenantSlug}) as{" "}
            <strong>{invite.role}</strong>.
          </p>
          <p className="muted">
            Use email <code>{invite.email}</code>. Status: {invite.status}. Expires {invite.expiresAt}.
          </p>
          <p>{invite.instructions}</p>
          {invite.status === "pending" ? (
            <div className="access-actions">
              <button type="button" onClick={() => void auth.register()}>
                Create account
              </button>
              <button type="button" className="secondary" onClick={() => void auth.login()}>
                Sign in
              </button>
            </div>
          ) : (
            <p className="muted">
              This invite is no longer pending. <Link to="/">Return home</Link>.
            </p>
          )}
        </>
      )}
      </div>
    </section>
  );
}
