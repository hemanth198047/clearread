-- Phase 3: URL sources, fetching status, job cancellation, fetch metadata

ALTER TABLE documents DROP CONSTRAINT documents_status_chk;
ALTER TABLE documents ADD CONSTRAINT documents_status_chk CHECK (status IN (
    'uploading', 'queued', 'fetching', 'processing', 'ready', 'failed', 'deleted', 'cancelled'
));

ALTER TABLE document_sources DROP CONSTRAINT document_sources_kind_chk;
ALTER TABLE document_sources ADD CONSTRAINT document_sources_kind_chk
    CHECK (source_kind IN ('upload', 'paste', 'url'));

ALTER TABLE document_sources
    ALTER COLUMN object_key DROP NOT NULL,
    ALTER COLUMN content_sha256 DROP NOT NULL,
    ALTER COLUMN byte_length DROP NOT NULL;

ALTER TABLE document_sources
    ADD COLUMN IF NOT EXISTS source_url TEXT,
    ADD COLUMN IF NOT EXISTS fetch_meta_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE jobs DROP CONSTRAINT jobs_status_chk;
ALTER TABLE jobs ADD CONSTRAINT jobs_status_chk CHECK (status IN (
    'pending', 'leased', 'succeeded', 'failed', 'dead', 'cancelled'
));

ALTER TABLE jobs
    ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_jobs_tenant_active
    ON jobs (tenant_id, status)
    WHERE status IN ('pending', 'leased');
