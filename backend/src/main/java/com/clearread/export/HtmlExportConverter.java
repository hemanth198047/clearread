package com.clearread.export;

import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class HtmlExportConverter implements ExportConverter {

    @Override
    public String targetFormatId() {
        return "html";
    }

    @Override
    public String converterId() {
        return "cleardoc-to-html-sanitized";
    }

    @Override
    public String converterVersion() {
        return "1.0.0";
    }

    @Override
    public ExportResult convert(ClearDoc doc) {
        String title = doc.metadata() != null && doc.metadata().title() != null
                ? doc.metadata().title() : "Export";
        StringBuilder body = new StringBuilder();
        for (Map<String, Object> node : doc.body()) {
            render(node, body);
        }
        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1"/>
                <meta name="clearread-artifact" content="generated"/>
                <title>%s</title>
                <style>
                  body{font-family:Georgia,serif;line-height:1.6;max-width:42rem;margin:2rem auto;padding:0 1rem;color:#2b2b2b;background:#f9f6f0}
                  pre,code{font-family:ui-monospace,monospace;font-size:.9rem}
                  pre{background:#efe9df;padding:1rem;overflow:auto;border-radius:.35rem}
                  table{border-collapse:collapse;width:100%%}
                  th,td{border:1px solid #d9d2c5;padding:.4rem .6rem;text-align:left}
                  th{background:#efe9df}
                  .banner{font-family:system-ui,sans-serif;font-size:.85rem;color:#6b6560;border-bottom:1px solid #d9d2c5;padding-bottom:.75rem;margin-bottom:1.5rem}
                  @media print{
                    @page{margin:1.5cm}
                    body{background:#fff;color:#000;max-width:none;margin:0;padding:0}
                    .banner{color:#333;border-color:#999;break-after:avoid}
                    pre,table,blockquote{break-inside:avoid}
                    a[href]::after{content:""}
                    h1,h2,h3{break-after:avoid}
                  }
                </style>
                </head>
                <body>
                <p class="banner">ClearRead generated HTML export — not the immutable original.</p>
                <h1>%s</h1>
                %s
                </body>
                </html>
                """.formatted(esc(title), esc(title), body);
        return new ExportResult(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8", ".html");
    }

    @SuppressWarnings("unchecked")
    private void render(Map<String, Object> node, StringBuilder sb) {
        String type = String.valueOf(node.get("type"));
        switch (type) {
            case "heading" -> {
                int level = Math.min(6, Math.max(1, ((Number) node.getOrDefault("level", 2)).intValue()));
                sb.append("<h").append(level).append('>')
                        .append(inline((List<Map<String, Object>>) node.get("children")))
                        .append("</h").append(level).append(">\n");
            }
            case "paragraph" -> sb.append("<p>").append(inline((List<Map<String, Object>>) node.get("children"))).append("</p>\n");
            case "codeBlock", "formattedSource" -> sb.append("<pre><code>")
                    .append(esc(String.valueOf(node.getOrDefault("text", ""))))
                    .append("</code></pre>\n");
            case "blockquote" -> {
                sb.append("<blockquote>\n");
                List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
                if (children != null) {
                    children.forEach(c -> render(c, sb));
                }
                sb.append("</blockquote>\n");
            }
            case "bulletList", "orderedList" -> {
                String tag = "bulletList".equals(type) ? "ul" : "ol";
                sb.append('<').append(tag).append(">\n");
                List<Map<String, Object>> items = (List<Map<String, Object>>) node.get("items");
                if (items != null) {
                    for (Map<String, Object> item : items) {
                        sb.append("<li>");
                        List<Map<String, Object>> children = (List<Map<String, Object>>) item.get("children");
                        if (children != null) {
                            children.forEach(c -> render(c, sb));
                        }
                        sb.append("</li>\n");
                    }
                }
                sb.append("</").append(tag).append(">\n");
            }
            case "thematicBreak" -> sb.append("<hr/>\n");
            case "dataTable" -> {
                sb.append("<table><thead><tr>");
                List<String> headers = (List<String>) node.get("headers");
                if (headers != null) {
                    for (String h : headers) {
                        sb.append("<th>").append(esc(csvSafe(h))).append("</th>");
                    }
                }
                sb.append("</tr></thead><tbody>");
                List<List<String>> rows = (List<List<String>>) node.get("rows");
                if (rows != null) {
                    for (List<String> row : rows) {
                        sb.append("<tr>");
                        for (String cell : row) {
                            sb.append("<td>").append(esc(csvSafe(cell))).append("</td>");
                        }
                        sb.append("</tr>");
                    }
                }
                sb.append("</tbody></table>\n");
            }
            case "treeNode" -> {
                sb.append("<div><strong>").append(esc(String.valueOf(node.getOrDefault("label", ""))))
                        .append("</strong>");
                if (node.get("valuePreview") != null) {
                    sb.append(" <code>").append(esc(String.valueOf(node.get("valuePreview")))).append("</code>");
                }
                sb.append("</div>\n");
                List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
                if (children != null) {
                    sb.append("<div style=\"margin-left:1rem\">");
                    children.forEach(c -> render(c, sb));
                    sb.append("</div>\n");
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
                String v = esc(String.valueOf(n.getOrDefault("value", "")));
                List<String> marks = (List<String>) n.get("marks");
                if (marks != null && marks.contains("bold")) {
                    v = "<strong>" + v + "</strong>";
                }
                if (marks != null && marks.contains("italic")) {
                    v = "<em>" + v + "</em>";
                }
                sb.append(v);
            } else if ("code".equals(type)) {
                sb.append("<code>").append(esc(String.valueOf(n.getOrDefault("value", "")))).append("</code>");
            } else if ("hardBreak".equals(type)) {
                sb.append("<br/>");
            } else if ("link".equals(type)) {
                String href = String.valueOf(n.getOrDefault("href", "#"));
                if (!(href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:") || href.startsWith("#"))) {
                    href = "#";
                }
                sb.append("<a href=\"").append(esc(href)).append("\">")
                        .append(inline((List<Map<String, Object>>) n.get("children"))).append("</a>");
            }
        }
        return sb.toString();
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s);
    }

    /** Mitigate CSV/formula injection when tabular values appear in HTML tables. */
    static String csvSafe(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        char c = cell.charAt(0);
        if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') {
            return "'" + cell;
        }
        return cell;
    }
}
