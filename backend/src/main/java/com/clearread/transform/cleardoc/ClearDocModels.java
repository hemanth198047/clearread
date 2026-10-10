package com.clearread.transform.cleardoc;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ClearDocModels {

    private ClearDocModels() {
    }

    public record ClearDoc(
            String schemaVersion,
            String documentId,
            String revisionId,
            String tenantId,
            Source source,
            Metadata metadata,
            List<OutlineItem> outline,
            List<Warning> warnings,
            List<Map<String, Object>> body
    ) {
    }

    public record Source(
            String formatId,
            String adapterId,
            String adapterVersion,
            String contentSha256,
            String originalFilename,
            long byteLength
    ) {
    }

    public record Metadata(String title, String language, Map<String, Object> frontMatter) {
    }

    public record OutlineItem(int level, String text, String nodeId) {
    }

    public record Warning(String code, String message, String nodeId) {
    }

    public record Provenance(String kind, String generator, SourceRange sourceRange, Double confidence) {
    }

    public record SourceRange(int startOffset, int endOffset) {
    }

    public static Map<String, Object> text(String value, List<String> marks) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("type", "text");
        node.put("value", value);
        if (marks != null && !marks.isEmpty()) {
            node.put("marks", marks);
        }
        return node;
    }

    public static Map<String, Object> heading(int level, String nodeId, List<Map<String, Object>> children, Provenance provenance) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("type", "heading");
        node.put("level", level);
        node.put("nodeId", nodeId);
        node.put("children", children);
        if (provenance != null) {
            node.put("provenance", provenance);
        }
        return node;
    }

    public static Map<String, Object> paragraph(String nodeId, List<Map<String, Object>> children, Provenance provenance) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("type", "paragraph");
        node.put("nodeId", nodeId);
        node.put("children", children);
        if (provenance != null) {
            node.put("provenance", provenance);
        }
        return node;
    }

    public static Map<String, Object> codeBlock(String nodeId, String language, String text, Provenance provenance) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("type", "codeBlock");
        node.put("nodeId", nodeId);
        if (language != null) {
            node.put("language", language);
        }
        node.put("text", text);
        if (provenance != null) {
            node.put("provenance", provenance);
        }
        return node;
    }

    public static Map<String, Object> bulletList(String nodeId, List<Map<String, Object>> items, Provenance provenance) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("type", "bulletList");
        node.put("nodeId", nodeId);
        node.put("items", items);
        if (provenance != null) {
            node.put("provenance", provenance);
        }
        return node;
    }

    public static Map<String, Object> orderedList(String nodeId, List<Map<String, Object>> items, Integer start, Provenance provenance) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("type", "orderedList");
        node.put("nodeId", nodeId);
        node.put("items", items);
        if (start != null) {
            node.put("start", start);
        }
        if (provenance != null) {
            node.put("provenance", provenance);
        }
        return node;
    }

    public static Map<String, Object> listItem(List<Map<String, Object>> children, Boolean checked) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("type", "listItem");
        node.put("children", children);
        if (checked != null) {
            node.put("checked", checked);
        }
        return node;
    }

    public static Map<String, Object> blockquote(String nodeId, List<Map<String, Object>> children, Provenance provenance) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("type", "blockquote");
        node.put("nodeId", nodeId);
        node.put("children", children);
        if (provenance != null) {
            node.put("provenance", provenance);
        }
        return node;
    }

    public static Map<String, Object> thematicBreak(String nodeId, Provenance provenance) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("type", "thematicBreak");
        node.put("nodeId", nodeId);
        if (provenance != null) {
            node.put("provenance", provenance);
        }
        return node;
    }

    public static List<Map<String, Object>> mutableBody() {
        return new ArrayList<>();
    }
}
