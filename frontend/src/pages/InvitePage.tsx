import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiFetch } from "../api/client";
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
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [token]);

  return (
    <section className="panel access-panel">
      <h1>Workspace invite</h1>
      {error && (
        <p role="alert" className="status-line">
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
            <p className="muted">This invite is no longer pending. <Link to="/">Return home</Link>.</p>
          )}
        </>
      )}
    </section>
  );
}
