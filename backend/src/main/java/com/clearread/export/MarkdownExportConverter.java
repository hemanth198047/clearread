package com.clearread.export;

import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class MarkdownExportConverter implements ExportConverter {

    @Override
    public String targetFormatId() {
        return "markdown";
    }

    @Override
    public String converterId() {
        return "cleardoc-to-markdown";
    }

    @Override
    public String converterVersion() {
        return "1.0.0";
    }

    @Override
    public ExportResult convert(ClearDoc doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- ClearRead generated export; not the immutable original -->\n");
        if (doc.metadata() != null && doc.metadata().title() != null) {
            sb.append("# ").append(doc.metadata().title()).append("\n\n");
        }
        for (Map<String, Object> node : doc.body()) {
            render(node, sb, 0);
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        return new ExportResult(bytes, "text/markdown; charset=utf-8", ".md");
    }

    @SuppressWarnings("unchecked")
    private void render(Map<String, Object> node, StringBuilder sb, int depth) {
        String type = String.valueOf(node.get("type"));
        switch (type) {
            case "heading" -> {
                int level = ((Number) node.getOrDefault("level", 2)).intValue();
                sb.append("#".repeat(Math.min(6, Math.max(1, level)))).append(' ');
                sb.append(inline((List<Map<String, Object>>) node.get("children"))).append("\n\n");
            }
            case "paragraph" -> sb.append(inline((List<Map<String, Object>>) node.get("children"))).append("\n\n");
            case "codeBlock", "formattedSource" -> {
                String lang = node.get("language") == null ? "" : String.valueOf(node.get("language"));
                sb.append("```").append(lang).append('\n').append(node.getOrDefault("text", "")).append("\n```\n\n");
            }
            case "blockquote" -> {
                List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
                if (children != null) {
                    for (Map<String, Object> c : children) {
                        StringBuilder inner = new StringBuilder();
                        render(c, inner, depth + 1);
                        for (String line : inner.toString().split("\n")) {
                            sb.append("> ").append(line).append('\n');
                        }
                    }
                    sb.append('\n');
                }
            }
            case "bulletList", "orderedList" -> {
                List<Map<String, Object>> items = (List<Map<String, Object>>) node.get("items");
                int i = 1;
                if (items != null) {
                    for (Map<String, Object> item : items) {
                        String prefix = "bulletList".equals(type) ? "- " : (i++ + ". ");
                        List<Map<String, Object>> children = (List<Map<String, Object>>) item.get("children");
                        StringBuilder inner = new StringBuilder();
                        if (children != null) {
                            for (Map<String, Object> c : children) {
                                render(c, inner, depth + 1);
                            }
                        }
                        sb.append(prefix).append(inner.toString().trim()).append("\n");
                    }
                    sb.append('\n');
                }
            }
            case "thematicBreak" -> sb.append("---\n\n");
            case "dataTable" -> {
                List<String> headers = (List<String>) node.get("headers");
                List<List<String>> rows = (List<List<String>>) node.get("rows");
                if (headers != null) {
                    sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
                    sb.append("| ").append(String.join(" | ", headers.stream().map(h -> "---").toList())).append(" |\n");
                }
                if (rows != null) {
                    for (List<String> row : rows) {
                        sb.append("| ").append(String.join(" | ", row)).append(" |\n");
                    }
                }
                sb.append('\n');
            }
            case "treeNode" -> {
                sb.append("- **").append(node.getOrDefault("label", "")).append("**");
                if (node.get("valuePreview") != null) {
                    sb.append(": `").append(node.get("valuePreview")).append('`');
                }
                sb.append('\n');
                List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
                if (children != null) {
                    for (Map<String, Object> c : children) {
                        render(c, sb, depth + 1);
                    }
                }
            }
            default -> {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String inline(List<Map<String, Object>> nodes) {
        if (nodes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> n : nodes) {
            String type = String.valueOf(n.get("type"));
            if ("text".equals(type)) {
                String v = String.valueOf(n.getOrDefault("value", ""));
                List<String> marks = (List<String>) n.get("marks");
                if (marks != null && marks.contains("bold")) {
                    v = "**" + v + "**";
                }
                if (marks != null && marks.contains("italic")) {
                    v = "*" + v + "*";
                }
                sb.append(v);
            } else if ("code".equals(type)) {
                sb.append('`').append(n.getOrDefault("value", "")).append('`');
            } else if ("hardBreak".equals(type)) {
                sb.append("  \n");
            } else if ("link".equals(type)) {
                sb.append('[').append(inline((List<Map<String, Object>>) n.get("children"))).append("](")
                        .append(n.getOrDefault("href", "#")).append(')');
            }
        }
        return sb.toString();
    }
}
