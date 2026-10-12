package com.clearread.transform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe text outline for Mermaid fenced blocks: never executes Mermaid/JS.
 * Size-limited, HTML/script-stripped, label extraction only.
 */
public final class MermaidTextFallback {

    public static final int MAX_CHARS = 32_768;
    public static final int MAX_LABELS = 40;

    private static final Pattern HTML_OR_SCRIPT = Pattern.compile(
            "(?i)<\\s*(script|iframe|object|embed|link|style)\\b[^>]*>.*?</\\s*\\1\\s*>|</?[^>]+>|javascript:|data:text/html"
    );
    private static final Pattern BRACKET_LABEL = Pattern.compile("\\[([^\\]\\n]{1,120})]");
    private static final Pattern PAREN_LABEL = Pattern.compile("\\(([^)\\n]{1,120})\\)");
    private static final Pattern BRACE_LABEL = Pattern.compile("\\{([^}\\n]{1,120})}");
    private static final Pattern PARTICIPANT = Pattern.compile(
            "(?im)^\\s*(?:participant|actor)\\s+([^\\s:#]{1,80})(?:\\s+as\\s+(.+))?$"
    );
    private static final Pattern EDGE_LABEL = Pattern.compile("\\|--([^|\\n]{1,80})--|-->\\|([^|\\n]{1,80})\\|");

    private MermaidTextFallback() {
    }

    public record Result(String outlineText, List<String> labels, boolean truncated, boolean scrubbed) {
    }

    public static boolean isMermaidLanguage(String info) {
        if (info == null || info.isBlank()) {
            return false;
        }
        String lang = info.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        return "mermaid".equals(lang);
    }

    /**
     * @return null when source is empty after scrub; otherwise a text outline marked for generated provenance.
     */
    public static Result summarize(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        boolean scrubbed = HTML_OR_SCRIPT.matcher(source).find();
        String text = HTML_OR_SCRIPT.matcher(source).replaceAll(" ");
        boolean truncated = false;
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
            truncated = true;
        }

        Set<String> labels = new LinkedHashSet<>();
        collect(BRACKET_LABEL, text, labels);
        collect(PAREN_LABEL, text, labels);
        collect(BRACE_LABEL, text, labels);
        Matcher participants = PARTICIPANT.matcher(text);
        while (participants.find() && labels.size() < MAX_LABELS) {
            String as = participants.group(2);
            String id = participants.group(1);
            labels.add(cleanLabel(as != null ? as : id));
        }
        Matcher edges = EDGE_LABEL.matcher(text);
        while (edges.find() && labels.size() < MAX_LABELS) {
            String a = edges.group(1);
            String b = edges.group(2);
            labels.add(cleanLabel(a != null ? a : b));
        }

        List<String> list = new ArrayList<>(labels);
        if (list.size() > MAX_LABELS) {
            list = list.subList(0, MAX_LABELS);
            truncated = true;
        }

        String diagramType = detectType(text);
        StringBuilder outline = new StringBuilder();
        outline.append("Mermaid diagram (text fallback — not executed).\n");
        outline.append("Type: ").append(diagramType).append('\n');
        if (list.isEmpty()) {
            outline.append("No extractable node labels; showing first lines of source:\n");
            String[] lines = text.split("\\R", 8);
            int n = Math.min(lines.length, 6);
            for (int i = 0; i < n; i++) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    outline.append("  · ").append(line.length() > 100 ? line.substring(0, 100) + "…" : line).append('\n');
                }
            }
        } else {
            outline.append("Labels:\n");
            for (String label : list) {
                outline.append("  · ").append(label).append('\n');
            }
        }
        if (truncated) {
            outline.append("(truncated for size/security limits)\n");
        }
        return new Result(outline.toString().trim(), List.copyOf(list), truncated, scrubbed);
    }

    private static void collect(Pattern pattern, String text, Set<String> labels) {
        Matcher m = pattern.matcher(text);
        while (m.find() && labels.size() < MAX_LABELS) {
            String raw = m.group(1);
            if (raw == null) {
                continue;
            }
            String cleaned = cleanLabel(raw);
            if (cleaned.length() >= 1 && !looksLikeSyntax(cleaned)) {
                labels.add(cleaned);
            }
        }
    }

    private static String cleanLabel(String raw) {
        return raw.replaceAll("\\s+", " ").trim();
    }

    private static boolean looksLikeSyntax(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.equals("flowchart") || lower.equals("sequenceDiagram".toLowerCase(Locale.ROOT))
                || lower.equals("graph") || lower.equals("td") || lower.equals("lr")
                || lower.startsWith("click ") || lower.contains("callback");
    }

    private static String detectType(String text) {
        String head = text.stripLeading();
        String first = head.split("\\R", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (first.startsWith("sequencediagram")) {
            return "sequence";
        }
        if (first.startsWith("classdiagram")) {
            return "class";
        }
        if (first.startsWith("statediagram")) {
            return "state";
        }
        if (first.startsWith("erdiagram")) {
            return "er";
        }
        if (first.startsWith("gantt")) {
            return "gantt";
        }
        if (first.startsWith("pie")) {
            return "pie";
        }
        if (first.startsWith("flowchart") || first.startsWith("graph")) {
            return "flowchart";
        }
        return "unknown";
    }
}
