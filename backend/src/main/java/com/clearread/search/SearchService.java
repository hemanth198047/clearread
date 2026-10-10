package com.clearread.search;

import com.clearread.documents.DocumentRepository;
import com.clearread.identity.UserRecord;
import com.clearread.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SearchService {

    private final JdbcTemplate jdbc;
    private final DocumentRepository documents;

    public SearchService(JdbcTemplate jdbc, DocumentRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    public void upsertIndex(UUID tenantId, UUID documentId, UUID revisionId, String title, String bodyText) {
        jdbc.update(
                """
                INSERT INTO document_search (document_id, tenant_id, revision_id, title, body_text)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (document_id) DO UPDATE SET
                  revision_id = EXCLUDED.revision_id,
                  title = EXCLUDED.title,
                  body_text = EXCLUDED.body_text
                """,
                documentId, tenantId, revisionId,
                title == null ? "" : title,
                bodyText == null ? "" : bodyText
        );
    }

    public void removeIndex(UUID documentId) {
        jdbc.update("DELETE FROM document_search WHERE document_id = ?", documentId);
    }

    /**
     * Workspace FTS. Only returns documents the user can read; never exposes unauthorized
     * counts/snippets. Query and snippets are escaped for HTML-safe display.
     */
    public Map<String, Object> search(UserRecord user, String rawQuery, int limit) {
        UUID tenantId = TenantContext.requireTenantId();
        String q = rawQuery == null ? "" : rawQuery.trim();
        if (q.isEmpty()) {
            return Map.of("query", "", "results", List.of(), "totalReturned", 0);
        }
        int capped = Math.min(Math.max(limit, 1), 50);
        // plainto_tsquery escapes special operators — safe against injection into tsquery
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT s.document_id, s.title, s.body_text,
                       ts_rank(s.search_vector, plainto_tsquery('english', ?)) AS rank,
                       ts_headline('english', left(s.body_text, 8000), plainto_tsquery('english', ?),
                         'StartSel=<mark>, StopSel=</mark>, MaxFragments=2, MaxWords=18, MinWords=6') AS headline
                FROM document_search s
                JOIN documents d ON d.id = s.document_id AND d.tenant_id = s.tenant_id
                WHERE s.tenant_id = ?
                  AND d.deleted_at IS NULL
                  AND s.search_vector @@ plainto_tsquery('english', ?)
                ORDER BY rank DESC
                LIMIT ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("documentId", rs.getObject("document_id", UUID.class).toString());
                    m.put("title", rs.getString("title"));
                    m.put("rank", rs.getDouble("rank"));
                    m.put("snippetHtml", sanitizeHeadline(rs.getString("headline")));
                    return m;
                },
                q, q, tenantId, q, capped * 4
        );

        List<Map<String, Object>> authorized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID docId = UUID.fromString((String) row.get("documentId"));
            if (documents.canRead(tenantId, docId, user.id(), TenantContext.roleOrNull())) {
                // Escape title; snippet already limited to mark tags
                row.put("title", HtmlUtils.htmlEscape(String.valueOf(row.get("title"))));
                authorized.add(row);
                if (authorized.size() >= capped) {
                    break;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", HtmlUtils.htmlEscape(q));
        out.put("results", authorized);
        out.put("totalReturned", authorized.size());
        // Intentionally omit global hit counts that could leak unauthorized docs
        return out;
    }

    static String sanitizeHeadline(String headline) {
        if (headline == null) {
            return "";
        }
        // Escape everything then re-enable only our mark tags from ts_headline
        String escaped = HtmlUtils.htmlEscape(headline);
        return escaped
                .replace("&lt;mark&gt;", "<mark>")
                .replace("&lt;/mark&gt;", "</mark>");
    }
}
