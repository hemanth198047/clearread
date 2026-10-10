package com.clearread.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class ReaderPreferencesRepository {

    private final JdbcTemplate jdbc;

    public ReaderPreferencesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findJson(UUID userId) {
        return jdbc.query(
                "SELECT preferences_json::text FROM user_reader_preferences WHERE user_id = ?",
                (rs, rowNum) -> rs.getString(1),
                userId
        ).stream().findFirst();
    }

    public void upsert(UUID userId, String preferencesJson) {
        jdbc.update(
                """
                INSERT INTO user_reader_preferences (user_id, preferences_json, updated_at)
                VALUES (?, ?::jsonb, now())
                ON CONFLICT (user_id) DO UPDATE
                SET preferences_json = EXCLUDED.preferences_json, updated_at = now()
                """,
                userId, preferencesJson
        );
    }
}
