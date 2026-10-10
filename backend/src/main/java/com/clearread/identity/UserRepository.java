package com.clearread.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private static final RowMapper<UserRecord> MAPPER = (rs, rowNum) -> new UserRecord(
            rs.getObject("id", UUID.class),
            rs.getString("idp_subject"),
            rs.getString("email"),
            rs.getString("display_name")
    );

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRecord> findBySubject(String subject) {
        return jdbc.query(
                "SELECT id, idp_subject, email, display_name FROM users WHERE idp_subject = ?",
                MAPPER,
                subject
        ).stream().findFirst();
    }

    public UserRecord upsert(String subject, String email, String displayName) {
        return findBySubject(subject).map(existing -> {
            jdbc.update(
                    "UPDATE users SET email = ?, display_name = ?, updated_at = now() WHERE id = ?",
                    email, displayName, existing.id()
            );
            return new UserRecord(existing.id(), subject, email, displayName);
        }).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO users (id, idp_subject, email, display_name)
                    VALUES (?, ?, ?, ?)
                    """,
                    id, subject, email, displayName
            );
            return new UserRecord(id, subject, email, displayName);
        });
    }
}
