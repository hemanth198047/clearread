-- Phase 2: per-user reader preferences (JSON document; tenant-optional for personal prefs)
CREATE TABLE user_reader_preferences (
    user_id         UUID PRIMARY KEY REFERENCES users(id),
    preferences_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
