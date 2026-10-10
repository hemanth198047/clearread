-- Phase 4: library, search, annotations, sharing, exports, workspace settings

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS favorite BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS last_opened_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS collection_id UUID;

CREATE TABLE collections (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    parent_id       UUID REFERENCES collections(id),
    name            TEXT NOT NULL,
    created_by      UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_collections_tenant ON collections(tenant_id) WHERE deleted_at IS NULL;

ALTER TABLE documents
    ADD CONSTRAINT documents_collection_fk FOREIGN KEY (collection_id) REFERENCES collections(id);

CREATE TABLE tags (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tags_tenant_name_uq UNIQUE (tenant_id, name)
);

CREATE TABLE document_tags (
    tenant_id   UUID NOT NULL,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    tag_id      UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, tag_id)
);

CREATE INDEX idx_document_tags_tenant ON document_tags(tenant_id, tag_id);

-- Full-text search index (workspace-scoped; queries always filter tenant + ACL)
CREATE TABLE document_search (
    document_id     UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL,
    revision_id     UUID,
    title           TEXT NOT NULL DEFAULT '',
    body_text       TEXT NOT NULL DEFAULT '',
    search_vector   TSVECTOR,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_search_tenant ON document_search(tenant_id);
CREATE INDEX idx_document_search_fts ON document_search USING GIN (search_vector);

CREATE OR REPLACE FUNCTION document_search_vector_update() RETURNS trigger AS $$
BEGIN
  NEW.search_vector :=
    setweight(to_tsvector('english', coalesce(NEW.title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(NEW.body_text, '')), 'B');
  NEW.updated_at := now();
  RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_document_search_vector
BEFORE INSERT OR UPDATE OF title, body_text ON document_search
FOR EACH ROW EXECUTE FUNCTION document_search_vector_update();

CREATE TABLE annotations (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    document_id     UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    revision_id     UUID,
    author_user_id  UUID NOT NULL REFERENCES users(id),
    kind            TEXT NOT NULL,
    visibility      TEXT NOT NULL,
    body_text       TEXT,
    color           TEXT,
    block_node_id   TEXT,
    source_start    INT,
    source_end      INT,
    resolved_at     TIMESTAMPTZ,
    anchor_status   TEXT NOT NULL DEFAULT 'anchored',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT annotations_kind_chk CHECK (kind IN ('highlight', 'bookmark', 'note', 'comment')),
    CONSTRAINT annotations_visibility_chk CHECK (visibility IN ('private', 'workspace')),
    CONSTRAINT annotations_anchor_chk CHECK (anchor_status IN ('anchored', 'uncertain', 'orphaned'))
);

CREATE INDEX idx_annotations_doc ON annotations(tenant_id, document_id) WHERE deleted_at IS NULL;

CREATE TABLE share_links (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    created_by          UUID NOT NULL REFERENCES users(id),
    grants_permission   TEXT NOT NULL DEFAULT 'read',
    token_hash          TEXT NOT NULL UNIQUE,
    label               TEXT,
    require_auth        BOOLEAN NOT NULL DEFAULT true,
    allow_download      BOOLEAN NOT NULL DEFAULT false,
    allow_export        BOOLEAN NOT NULL DEFAULT false,
    expires_at          TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT share_links_perm_chk CHECK (grants_permission IN ('read'))
);

CREATE INDEX idx_share_links_doc ON share_links(tenant_id, document_id);

CREATE TABLE preview_tokens (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL,
    document_id             UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    revision_id             UUID,
    created_by              UUID NOT NULL REFERENCES users(id),
    token_hash              TEXT NOT NULL UNIQUE,
    expires_at              TIMESTAMPTZ NOT NULL,
    max_views               INT,
    view_count              INT NOT NULL DEFAULT 0,
    allow_original_download BOOLEAN NOT NULL DEFAULT false,
    revoked_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_preview_tokens_doc ON preview_tokens(tenant_id, document_id);

CREATE TABLE document_exports (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    document_id         UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    revision_id         UUID,
    target_format       TEXT NOT NULL,
    status              TEXT NOT NULL,
    object_key          TEXT,
    content_sha256      CHAR(64),
    byte_length         BIGINT,
    converter_id        TEXT NOT NULL,
    converter_version   TEXT NOT NULL,
    artifact_kind       TEXT NOT NULL DEFAULT 'generated',
    created_by          UUID NOT NULL REFERENCES users(id),
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_exports_status_chk CHECK (status IN ('pending', 'ready', 'failed')),
    CONSTRAINT document_exports_kind_chk CHECK (artifact_kind IN ('generated'))
);

CREATE INDEX idx_document_exports_doc ON document_exports(tenant_id, document_id, created_at DESC);

CREATE TABLE workspace_settings (
    tenant_id                   UUID PRIMARY KEY REFERENCES tenants(id),
    sharing_enabled             BOOLEAN NOT NULL DEFAULT true,
    anonymous_preview_enabled   BOOLEAN NOT NULL DEFAULT true,
    retention_days              INT,
    max_upload_bytes            BIGINT NOT NULL DEFAULT 10485760,
    max_documents               INT,
    default_export_download     BOOLEAN NOT NULL DEFAULT true,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO workspace_settings (tenant_id)
SELECT id FROM tenants
ON CONFLICT DO NOTHING;
