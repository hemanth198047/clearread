-- Per-tenant AI connection settings (Ollama URL/model, external URL/key/model).
-- Overrides env defaults when a row exists. API keys are stored server-side and never echoed full.

CREATE TABLE IF NOT EXISTS ai_connection_settings (
    tenant_id UUID PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    ollama_enabled BOOLEAN NOT NULL DEFAULT false,
    ollama_base_url TEXT NOT NULL DEFAULT 'http://host.docker.internal:11434',
    ollama_model TEXT NOT NULL DEFAULT 'llama3.2',
    external_enabled BOOLEAN NOT NULL DEFAULT false,
    external_base_url TEXT NOT NULL DEFAULT '',
    external_api_key TEXT NOT NULL DEFAULT '',
    external_model TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
