package com.clearread.jobs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Repository
public class JobRepository {

    public record JobRecord(
            UUID id,
            UUID tenantId,
            String type,
            String payloadJson,
            String status,
            String idempotencyKey,
            int attempt,
            int maxAttempts,
            String lastError,
            UUID leaseToken,
            boolean cancelRequested
    ) {
    }

    private static final RowMapper<JobRecord> MAPPER = (rs, rowNum) -> new JobRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("type"),
            rs.getString("payload_json"),
            rs.getString("status"),
            rs.getString("idempotency_key"),
            rs.getInt("attempt"),
            rs.getInt("max_attempts"),
            rs.getString("last_error"),
            rs.getObject("lease_token", UUID.class),
            rs.getBoolean("cancel_requested")
    );

    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(UUID id, UUID tenantId, String type, String payloadJson, String idempotencyKey) {
        enqueueWithMaxAttempts(id, tenantId, type, payloadJson, idempotencyKey, 5);
    }

    public void enqueueWithMaxAttempts(
            UUID id, UUID tenantId, String type, String payloadJson, String idempotencyKey, int maxAttempts
    ) {
        jdbc.update(
                """
                INSERT INTO jobs (id, tenant_id, type, payload_json, status, idempotency_key, max_attempts)
                VALUES (?, ?, ?, ?::jsonb, 'pending', ?, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """,
                id, tenantId, type, payloadJson, idempotencyKey, Math.max(1, maxAttempts)
        );
    }

    /**
     * Claim due jobs with SKIP LOCKED, recovering expired leases.
     * Skips tenants already at max concurrent leased jobs.
     */
    public List<JobRecord> claim(String workerId, int leaseSeconds, int limit, int maxConcurrentPerTenant) {
        return jdbc.query(
                """
                WITH candidates AS (
                  SELECT j.id
                  FROM jobs j
                  WHERE j.status IN ('pending', 'leased')
                    AND j.cancel_requested = false
                    AND j.next_run_at <= now()
                    AND (j.lease_expires_at IS NULL OR j.lease_expires_at < now() OR j.status = 'pending')
                    AND (
                      SELECT COUNT(*) FROM jobs active
                      WHERE active.tenant_id = j.tenant_id
                        AND active.status = 'leased'
                        AND active.lease_expires_at > now()
                    ) < ?
                  ORDER BY j.next_run_at
                  FOR UPDATE OF j SKIP LOCKED
                  LIMIT ?
                )
                UPDATE jobs j
                SET status = 'leased',
                    attempt = j.attempt + 1,
                    lease_owner = ?,
                    lease_token = gen_random_uuid(),
                    lease_expires_at = now() + (? || ' seconds')::interval,
                    updated_at = now()
                FROM candidates c
                WHERE j.id = c.id
                RETURNING j.id, j.tenant_id, j.type, j.payload_json::text, j.status, j.idempotency_key,
                          j.attempt, j.max_attempts, j.last_error, j.lease_token, j.cancel_requested
                """,
                MAPPER,
                maxConcurrentPerTenant, limit, workerId, leaseSeconds
        );
    }

    public boolean succeed(UUID jobId, UUID leaseToken) {
        int n = jdbc.update(
                """
                UPDATE jobs
                SET status = 'succeeded', updated_at = now(), lease_expires_at = NULL
                WHERE id = ? AND lease_token = ? AND status = 'leased'
                """,
                jobId, leaseToken
        );
        return n > 0;
    }

    public void fail(UUID jobId, UUID leaseToken, String error, int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            jdbc.update(
                    """
                    UPDATE jobs SET status = 'dead', last_error = ?, updated_at = now(), lease_expires_at = NULL
                    WHERE id = ? AND (lease_token = ? OR lease_token IS NULL)
                    """,
                    truncate(error), jobId, leaseToken
            );
        } else {
            long base = Math.min(300, (long) Math.pow(2, attempt));
            long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 2 + 1));
            long backoffSeconds = base + jitter;
            jdbc.update(
                    """
                    UPDATE jobs
                    SET status = 'pending',
                        last_error = ?,
                        next_run_at = now() + (? || ' seconds')::interval,
                        lease_expires_at = NULL,
                        updated_at = now()
                    WHERE id = ? AND (lease_token = ? OR lease_token IS NULL)
                    """,
                    truncate(error), backoffSeconds, jobId, leaseToken
            );
        }
    }

    public boolean requestCancel(UUID tenantId, UUID jobId) {
        int n = jdbc.update(
                """
                UPDATE jobs
                SET cancel_requested = true,
                    status = CASE WHEN status IN ('pending') THEN 'cancelled' ELSE status END,
                    updated_at = now()
                WHERE id = ? AND tenant_id = ? AND status IN ('pending', 'leased')
                """,
                jobId, tenantId
        );
        return n > 0;
    }

    public void markCancelled(UUID jobId, UUID leaseToken) {
        jdbc.update(
                """
                UPDATE jobs SET status = 'cancelled', updated_at = now(), lease_expires_at = NULL
                WHERE id = ? AND (lease_token = ? OR lease_token IS NULL)
                """,
                jobId, leaseToken
        );
    }

    public Optional<JobRecord> find(UUID jobId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, type, payload_json::text, status, idempotency_key, attempt, max_attempts,
                       last_error, lease_token, cancel_requested
                FROM jobs WHERE id = ?
                """,
                MAPPER,
                jobId
        ).stream().findFirst();
    }

    public Optional<JobRecord> findByTenant(UUID tenantId, UUID jobId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, type, payload_json::text, status, idempotency_key, attempt, max_attempts,
                       last_error, lease_token, cancel_requested
                FROM jobs WHERE tenant_id = ? AND id = ?
                """,
                MAPPER,
                tenantId, jobId
        ).stream().findFirst();
    }

    public List<JobRecord> listForDocument(UUID tenantId, UUID documentId, int limit) {
        return jdbc.query(
                """
                SELECT id, tenant_id, type, payload_json::text, status, idempotency_key, attempt, max_attempts,
                       last_error, lease_token, cancel_requested
                FROM jobs
                WHERE tenant_id = ?
                  AND payload_json->>'documentId' = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
                MAPPER,
                tenantId, documentId.toString(), limit
        );
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 2000 ? error.substring(0, 2000) : error;
    }
}
