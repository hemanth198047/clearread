package com.clearread.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic local adapter for demos/tests. No network. Same SPI as Ollama/external.
 */
@Component
public class MockLocalAiProvider implements AiProvider {

    public static final String ID = "mock-local";

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String modelId() {
        return "mock-local-v1";
    }

    @Override
    public EnrichmentResponse enrich(EnrichmentRequest request) {
        Map<String, Object> proposal = switch (request.kind()) {
            case "headings" -> headings(request.excerpts());
            case "segmentation" -> segmentation(request.excerpts());
            case "summary" -> summary(request.excerpts());
            case "glossary" -> glossary(request.excerpts());
            default -> throw new IllegalArgumentException("Unknown kind: " + request.kind());
        };
        int tokensIn = request.excerpts().stream().mapToInt(e -> Math.max(1, e.text().length() / 4)).sum();
        int tokensOut = 80;
        return new EnrichmentResponse(providerId(), modelId(), proposal, tokensIn, tokensOut);
    }

    private static Map<String, Object> headings(List<BlockExcerpt> excerpts) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (BlockExcerpt e : excerpts) {
            if (!"heading".equals(e.type()) && !"paragraph".equals(e.type())) {
                continue;
            }
            String suggested = titleCase(firstSentence(e.text()));
            if (suggested.isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("blockNodeId", e.blockNodeId());
            row.put("suggestedText", suggested);
            row.put("sourceStart", e.sourceStart());
            row.put("sourceEnd", e.sourceEnd());
            row.put("generated", true);
            row.put("label", "AI-suggested");
            suggestions.add(row);
        }
        return Map.of("suggestions", suggestions, "artifactKind", "ai-derived", "label", "AI-generated headings");
    }

    private static Map<String, Object> segmentation(List<BlockExcerpt> excerpts) {
        List<Map<String, Object>> segments = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        String title = "Section 1";
        int idx = 1;
        for (BlockExcerpt e : excerpts) {
            if ("heading".equals(e.type()) && !ids.isEmpty()) {
                segments.add(segment(title, ids, "Boundary before heading"));
                ids = new ArrayList<>();
                title = "Section " + (++idx);
            }
            ids.add(e.blockNodeId());
            if ("heading".equals(e.type()) && !e.text().isBlank()) {
                title = firstSentence(e.text());
            }
        }
        if (!ids.isEmpty()) {
            segments.add(segment(title, ids, "Trailing segment"));
        }
        return Map.of("segments", segments, "artifactKind", "ai-derived", "label", "AI-generated segmentation");
    }

    private static Map<String, Object> segment(String title, List<String> ids, String rationale) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("blockNodeIds", List.copyOf(ids));
        m.put("rationale", rationale);
        m.put("generated", true);
        return m;
    }

    private static Map<String, Object> summary(List<BlockExcerpt> excerpts) {
        List<String> sourceIds = excerpts.stream().map(BlockExcerpt::blockNodeId).toList();
        StringBuilder sb = new StringBuilder();
        for (BlockExcerpt e : excerpts) {
            String s = firstSentence(e.text());
            if (!s.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(s);
            }
            if (sb.length() > 400) {
                break;
            }
        }
        String summary = sb.isEmpty() ? "No extractable summary from excerpts." : sb.toString();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", summary);
        m.put("sourceBlockIds", sourceIds);
        m.put("keyPoints", excerpts.stream()
                .limit(5)
                .map(e -> firstSentence(e.text()))
                .filter(s -> !s.isBlank())
                .toList());
        m.put("artifactKind", "ai-derived");
        m.put("label", "AI-generated summary");
        m.put("generated", true);
        return m;
    }

    private static Map<String, Object> glossary(List<BlockExcerpt> excerpts) {
        List<Map<String, Object>> terms = new ArrayList<>();
        for (BlockExcerpt e : excerpts) {
            for (String word : e.text().split("[^A-Za-z0-9_-]+")) {
                if (word.length() < 6 || !Character.isUpperCase(word.charAt(0))) {
                    continue;
                }
                if (terms.stream().anyMatch(t -> word.equalsIgnoreCase(String.valueOf(t.get("term"))))) {
                    continue;
                }
                Map<String, Object> term = new LinkedHashMap<>();
                term.put("term", word);
                term.put("definition", "Key term appearing near block " + e.blockNodeId());
                term.put("sourceBlockId", e.blockNodeId());
                term.put("generated", true);
                terms.add(term);
                if (terms.size() >= 8) {
                    break;
                }
            }
            if (terms.size() >= 8) {
                break;
            }
        }
        return Map.of("terms", terms, "artifactKind", "ai-derived", "label", "AI-generated glossary");
    }

    private static String firstSentence(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim().replaceAll("\\s+", " ");
        int cut = t.length();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                cut = i + 1;
                break;
            }
        }
        return t.substring(0, Math.min(cut, 160)).trim();
    }

    private static String titleCase(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String[] parts = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(p.substring(0, 1).toUpperCase(Locale.ROOT));
            if (p.length() > 1) {
                out.append(p.substring(1));
            }
        }
        return out.toString();
    }
}
