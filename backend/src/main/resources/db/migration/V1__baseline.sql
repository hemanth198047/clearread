-- ClearRead Phase 1 baseline: tenants, users, memberships, documents, revisions, sources, jobs
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    idp_subject     TEXT NOT NULL UNIQUE,
    email           CITEXT,
    display_name    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tenants (
    id              UUID PRIMARY KEY,
    slug            TEXT NOT NULL UNIQUE,
    name            TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tenants_status_chk CHECK (status IN ('active', 'suspended'))
);

CREATE TABLE memberships (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    user_id         UUID NOT NULL REFERENCES users(id),
    role            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT memberships_role_chk CHECK (role IN ('viewer', 'author', 'admin')),
    CONSTRAINT memberships_tenant_user_uq UNIQUE (tenant_id, user_id)
);

CREATE INDEX idx_memberships_user ON memberships(user_id);

CREATE TABLE branding_settings (
    tenant_id           UUID PRIMARY KEY REFERENCES tenants(id),
    product_name        TEXT NOT NULL,
    logo_object_key     TEXT,
    primary_color       TEXT NOT NULL,
    secondary_color     TEXT NOT NULL,
    favicon_object_key  TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE documents (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenants(id),
    title                   TEXT NOT NULL,
    format_id               TEXT NOT NULL,
    status                  TEXT NOT NULL,
    original_object_key     TEXT,
    original_sha256         CHAR(64),
    original_byte_length    BIGINT,
    original_filename       TEXT,
    created_by              UUID NOT NULL REFERENCES users(id),
    current_revision_id     UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMPTZ,
    CONSTRAINT documents_status_chk CHECK (status IN (
        'uploading', 'queued', 'processing', 'ready', 'failed', 'deleted'
    ))
);

CREATE INDEX idx_documents_tenant_updated ON documents(tenant_id, updated_at DESC);
CREATE INDEX idx_documents_tenant_status ON documents(tenant_id, status);

CREATE TABLE document_sources (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    document_id         UUID NOT NULL REFERENCES documents(id),
    source_kind         TEXT NOT NULL,
    object_key          TEXT NOT NULL,
    content_sha256      CHAR(64) NOT NULL,
    byte_length         BIGINT NOT NULL,
    content_type        TEXT,
    original_filename   TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_sources_kind_chk CHECK (source_kind IN ('upload', 'paste')),
    CONSTRAINT document_sources_doc_uq UNIQUE (document_id)
);

CREATE TABLE document_revisions (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL,
    document_id             UUID NOT NULL REFERENCES documents(id),
    revision_number         INT NOT NULL,
    cleardoc_object_key     TEXT NOT NULL,
    cleardoc_sha256         CHAR(64) NOT NULL,
    schema_version          TEXT NOT NULL,
    adapter_id              TEXT NOT NULL,
    adapter_version         TEXT NOT NULL,
    registry_version        TEXT NOT NULL,
    warnings_json           JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_revisions_doc_rev_uq UNIQUE (document_id, revision_number)
);

CREATE INDEX idx_document_revisions_tenant ON document_revisions(tenant_id, document_id);

ALTER TABLE documents
    ADD CONSTRAINT documents_current_revision_fk
    FOREIGN KEY (current_revision_id) REFERENCES document_revisions(id);

CREATE TABLE document_acls (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    document_id     UUID NOT NULL REFERENCES documents(id),
    principal_type  TEXT NOT NULL,
    principal_id    TEXT NOT NULL,
    permission      TEXT NOT NULL,
    CONSTRAINT document_acls_principal_chk CHECK (principal_type IN ('user', 'role')),
    CONSTRAINT document_acls_perm_chk CHECK (permission IN ('read', 'write', 'share', 'admin')),
    CONSTRAINT document_acls_uq UNIQUE (document_id, principal_type, principal_id, permission)
);

CREATE TABLE jobs (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    type                TEXT NOT NULL,
    payload_json        JSONB NOT NULL,
    status              TEXT NOT NULL,
    idempotency_key     TEXT NOT NULL,
    attempt             INT NOT NULL DEFAULT 0,
    max_attempts        INT NOT NULL DEFAULT 5,
    next_run_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner         TEXT,
    lease_token         UUID,
    lease_expires_at    TIMESTAMPTZ,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT jobs_status_chk CHECK (status IN ('pending', 'leased', 'succeeded', 'failed', 'dead')),
    CONSTRAINT jobs_idempotency_uq UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_jobs_claim ON jobs(status, next_run_at);

CREATE TABLE audit_events (
    id              UUID PRIMARY KEY,
    tenant_id       UUID,
    actor_user_id   UUID,
    actor_type      TEXT NOT NULL,
    action          TEXT NOT NULL,
    resource_type   TEXT NOT NULL,
    resource_id     TEXT NOT NULL,
    ip              INET,
    meta_json       JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_tenant_created ON audit_events(tenant_id, created_at DESC);

-- Seed demo tenant for local Keycloak user bootstrap (membership created on first login)
INSERT INTO tenants (id, slug, name, status)
VALUES ('11111111-1111-1111-1111-111111111111', 'demo', 'Demo Workspace', 'active');

INSERT INTO branding_settings (tenant_id, product_name, primary_color, secondary_color)
VALUES ('11111111-1111-1111-1111-111111111111', 'ClearRead', '#1e293b', '#f59e0b');
