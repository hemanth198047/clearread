package com.clearread.export;

/**
 * Phase 1 seam only — full conversion suite ships in Phase 4 (MD + sanitized HTML).
 * Implementations convert ClearDoc (derived IR) into readable formats and must mark
 * outputs as generated artifacts distinct from immutable originals.
 */
public interface ExportConverter {

    String targetFormatId();

    String converterId();

    String converterVersion();
}
