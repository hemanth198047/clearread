package com.clearread.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EnrichmentRepository {

    public record EnrichmentRecord(
            UUID id,
            UUID tenantId,
            UUID documentId,
            UUID revisionId,
            String kind,
            String status,
            String providerId,
            String modelId,
            String promptTemplateId,
            String promptVersion,
            String configHash,
            String cacheKey,
            String proposalJson,
            String validationJson,
            UUID createdBy,
            String lastError
    ) {
    }

    private static final RowMapper<EnrichmentRecord> MAPPER = (rs, i) -> new EnrichmentRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("document_id", UUID.class),
            rs.getObject("revision_id", UUID.class),
            rs.getString("kind"),
            rs.getString("status"),
            rs.getString("provider_id"),
            rs.getString("model_id"),
            rs.getString("prompt_template_id"),
            rs.getString("prompt_version"),
            rs.getString("config_hash"),
            rs.getString("cache_key"),
            rs.getString("proposal_json"),
            rs.getString("validation_json"),
            rs.getObject("created_by", UUID.class),
            rs.getString("last_error")
    );

    private final JdbcTemplate jdbc;

    public EnrichmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPending(EnrichmentRecord r) {
        jdbc.update(
                """
                INSERT INTO ai_enrichments (
                  id, tenant_id, document_id, revision_id, kind, status, provider_id, model_id,
                  prompt_template_id, prompt_version, config_hash, cache_key, created_by
                ) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?, ?, ?)
                """,
                r.id(), r.tenantId(), r.documentId(), r.revisionId(), r.kind(), r.providerId(), r.modelId(),
                r.promptTemplateId(), r.promptVersion(), r.configHash(), r.cacheKey(), r.createdBy()
        );
    }

    public Optional<EnrichmentRecord> findCachedReady(UUID tenantId, String cacheKey) {
        return jdbc.query(
                """
                SELECT id, tenant_id, document_id, revision_id, kind, status, provider_id, model_id,
                       prompt_template_id, prompt_version, config_hash, cache_key,
                       proposal_json::text, validation_json::text, created_by, last_error
                FROM ai_enrichments
                WHERE tenant_id = ? AND cache_key = ? AND status IN ('ready', 'accepted')
                ORDER BY created_at DESC LIMIT 1
                """,
                MAPPER, tenantId, cacheKey
        ).stream().findFirst();
    }

    public Optional<EnrichmentRecord> find(UUID tenantId, UUID id) {
        return jdbc.query(
                """
                SELECT id, tenant_id, document_id, revision_id, kind, status, provider_id, model_id,
                       prompt_template_id, prompt_version, config_hash, cache_key,
                       proposal_json::text, validation_json::text, created_by, last_error
                FROM ai_enrichments WHERE tenant_id = ? AND id = ?
                """,
                MAPPER, tenantId, id
        ).stream().findFirst();
    }

    public List<EnrichmentRecord> listForDocument(UUID tenantId, UUID documentId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, document_id, revision_id, kind, status, provider_id, model_id,
                       prompt_template_id, prompt_version, config_hash, cache_key,
                       proposal_json::text, validation_json::text, created_by, last_error
                FROM ai_enrichments
                WHERE tenant_id = ? AND document_id = ?
                ORDER BY created_at DESC
                LIMIT 50
                """,
                MAPPER, tenantId, documentId
        );
    }

    public void markReady(UUID id, String modelId, String proposalJson, String validationJson) {
        jdbc.update(
                """
                UPDATE ai_enrichments
                SET status = 'ready', model_id = ?, proposal_json = ?::jsonb, validation_json = ?::jsonb,
                    last_error = NULL, updated_at = now()
                WHERE id = ?
                """,
                modelId, proposalJson, validationJson, id
        );
    }

    public void markFailed(UUID id, String error) {
        jdbc.update(
                """
                UPDATE ai_enrichments
                SET status = 'failed', last_error = ?, updated_at = now()
                WHERE id = ?
                """,
                truncate(error), id
        );
    }

    public boolean accept(UUID tenantId, UUID id, UUID userId) {
        int n = jdbc.update(
                """
                UPDATE ai_enrichments
                SET status = 'accepted', accepted_at = now(), accepted_by = ?, updated_at = now()
                WHERE id = ? AND tenant_id = ? AND status = 'ready'
                """,
                userId, id, tenantId
        );
        return n > 0;
    }

    public boolean reject(UUID tenantId, UUID id) {
        int n = jdbc.update(
                """
                UPDATE ai_enrichments
                SET status = 'rejected', updated_at = now()
                WHERE id = ? AND tenant_id = ? AND status IN ('ready', 'pending')
                """,
                id, tenantId
        );
        return n > 0;
    }

    public record PromptTemplate(String id, String version, String kind, String bodyText) {
    }

    public Optional<PromptTemplate> latestTemplate(String kind) {
        return jdbc.query(
                """
                SELECT id, version, kind, body_text FROM ai_prompt_templates
                WHERE kind = ? ORDER BY created_at DESC LIMIT 1
                """,
                (rs, i) -> new PromptTemplate(
                        rs.getString("id"), rs.getString("version"), rs.getString("kind"), rs.getString("body_text")
                ),
                kind
        ).stream().findFirst();
    }

    public Optional<PromptTemplate> systemGuard() {
        return latestTemplate("system_guard");
    }

    public int usageToday(UUID tenantId) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM ai_usage
                WHERE tenant_id = ? AND created_at >= date_trunc('day', now())
                """,
                Integer.class, tenantId
        );
        return n == null ? 0 : n;
    }

    public void recordUsage(UUID tenantId, UUID documentId, UUID enrichmentId, String providerId, String kind,
                            int tokensIn, int tokensOut) {
        jdbc.update(
                """
                INSERT INTO ai_usage (id, tenant_id, document_id, enrichment_id, provider_id, kind, tokens_in, tokens_out)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), tenantId, documentId, enrichmentId, providerId, kind, tokensIn, tokensOut
        );
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 2000 ? error.substring(0, 2000) : error;
    }
}
