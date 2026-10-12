-- Pending workspace invites (email + role). No outbound email in v1 — admins copy the invite link.
CREATE TABLE workspace_invites (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email           TEXT NOT NULL,
    role            TEXT NOT NULL,
    token           TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'pending',
    invited_by      UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    accepted_at     TIMESTAMPTZ,
    accepted_user_id UUID REFERENCES users(id),
    CONSTRAINT workspace_invites_role_chk CHECK (role IN ('viewer', 'author', 'admin')),
    CONSTRAINT workspace_invites_status_chk CHECK (status IN ('pending', 'accepted', 'revoked', 'expired')),
    CONSTRAINT workspace_invites_token_uq UNIQUE (token)
);

CREATE INDEX idx_workspace_invites_tenant ON workspace_invites(tenant_id, status);
CREATE INDEX idx_workspace_invites_email ON workspace_invites(LOWER(email), status);
