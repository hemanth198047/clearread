package com.clearread.jobs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
            String lastError
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
            rs.getString("last_error")
    );

    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(UUID id, UUID tenantId, String type, String payloadJson, String idempotencyKey) {
        jdbc.update(
                """
                INSERT INTO jobs (id, tenant_id, type, payload_json, status, idempotency_key)
                VALUES (?, ?, ?, ?::jsonb, 'pending', ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """,
                id, tenantId, type, payloadJson, idempotencyKey
        );
    }

    public List<JobRecord> claim(String workerId, int leaseSeconds, int limit) {
        return jdbc.query(
                """
                WITH cte AS (
                  SELECT id FROM jobs
                  WHERE status IN ('pending', 'leased')
                    AND next_run_at <= now()
                    AND (lease_expires_at IS NULL OR lease_expires_at < now() OR status = 'pending')
                  ORDER BY next_run_at
                  FOR UPDATE SKIP LOCKED
                  LIMIT ?
                )
                UPDATE jobs j
                SET status = 'leased',
                    attempt = j.attempt + 1,
                    lease_owner = ?,
                    lease_token = gen_random_uuid(),
                    lease_expires_at = now() + (? || ' seconds')::interval,
                    updated_at = now()
                FROM cte
                WHERE j.id = cte.id
                RETURNING j.id, j.tenant_id, j.type, j.payload_json::text, j.status, j.idempotency_key,
                          j.attempt, j.max_attempts, j.last_error
                """,
                MAPPER,
                limit, workerId, leaseSeconds
        );
    }

    public void succeed(UUID jobId) {
        jdbc.update(
                "UPDATE jobs SET status = 'succeeded', updated_at = now(), lease_expires_at = NULL WHERE id = ?",
                jobId
        );
    }

    public void fail(UUID jobId, String error, int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            jdbc.update(
                    """
                    UPDATE jobs SET status = 'dead', last_error = ?, updated_at = now(), lease_expires_at = NULL
                    WHERE id = ?
                    """,
                    error, jobId
            );
        } else {
            long backoffSeconds = Math.min(300, (long) Math.pow(2, attempt));
            jdbc.update(
                    """
                    UPDATE jobs
                    SET status = 'pending',
                        last_error = ?,
                        next_run_at = now() + (? || ' seconds')::interval,
                        lease_expires_at = NULL,
                        updated_at = now()
                    WHERE id = ?
                    """,
                    error, backoffSeconds, jobId
            );
        }
    }

    public Optional<JobRecord> find(UUID jobId) {
        return jdbc.query(
                """
                SELECT id, tenant_id, type, payload_json::text, status, idempotency_key, attempt, max_attempts, last_error
                FROM jobs WHERE id = ?
                """,
                MAPPER,
                jobId
        ).stream().findFirst();
    }
}
