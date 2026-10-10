package com.clearread.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema-light validation of AI proposals. Detects missing/duplicated source coverage where feasible.
 */
public final class EnrichmentValidator {

    private EnrichmentValidator() {
    }

    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings, Map<String, Object> report) {
    }

    @SuppressWarnings("unchecked")
    public static ValidationResult validate(String kind, Map<String, Object> proposal, Set<String> knownBlockIds) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (proposal == null) {
            errors.add("proposal is null");
            return finish(false, errors, warnings, Map.of());
        }
        Object label = proposal.get("label");
        if (label == null || String.valueOf(label).isBlank()) {
            warnings.add("missing generated-content label");
        }
        Set<String> referenced = new HashSet<>();
        Set<String> duplicates = new HashSet<>();

        switch (kind) {
            case "headings" -> {
                Object suggestions = proposal.get("suggestions");
                if (!(suggestions instanceof List<?> list)) {
                    errors.add("headings requires suggestions[]");
                } else {
                    for (Object row : list) {
                        if (!(row instanceof Map<?, ?> m)) {
                            errors.add("suggestion must be object");
                            continue;
                        }
                        String id = str(m.get("blockNodeId"));
                        if (id.isBlank()) {
                            errors.add("suggestion missing blockNodeId");
                        } else {
                            track(id, referenced, duplicates);
                            if (!knownBlockIds.contains(id)) {
                                warnings.add("unknown blockNodeId: " + id);
                            }
                        }
                        if (str(m.get("suggestedText")).isBlank()) {
                            errors.add("suggestion missing suggestedText");
                        }
                    }
                }
            }
            case "segmentation" -> {
                Object segments = proposal.get("segments");
                if (!(segments instanceof List<?> list) || list.isEmpty()) {
                    errors.add("segmentation requires segments[]");
                } else {
                    for (Object row : list) {
                        if (!(row instanceof Map<?, ?> m)) {
                            errors.add("segment must be object");
                            continue;
                        }
                        Object ids = m.get("blockNodeIds");
                        if (!(ids instanceof List<?> idList) || idList.isEmpty()) {
                            errors.add("segment missing blockNodeIds");
                            continue;
                        }
                        for (Object idObj : idList) {
                            String id = String.valueOf(idObj);
                            track(id, referenced, duplicates);
                            if (!knownBlockIds.contains(id)) {
                                warnings.add("unknown blockNodeId: " + id);
                            }
                        }
                    }
                }
            }
            case "summary" -> {
                if (str(proposal.get("summary")).isBlank()) {
                    errors.add("summary missing summary text");
                }
                Object ids = proposal.get("sourceBlockIds");
                if (ids instanceof List<?> idList) {
                    for (Object idObj : idList) {
                        String id = String.valueOf(idObj);
                        track(id, referenced, duplicates);
                        if (!knownBlockIds.contains(id)) {
                            warnings.add("unknown blockNodeId: " + id);
                        }
                    }
                } else {
                    warnings.add("summary missing sourceBlockIds");
                }
            }
            case "glossary" -> {
                Object terms = proposal.get("terms");
                if (!(terms instanceof List<?> list)) {
                    errors.add("glossary requires terms[]");
                } else {
                    Set<String> termNames = new HashSet<>();
                    for (Object row : list) {
                        if (!(row instanceof Map<?, ?> m)) {
                            errors.add("term must be object");
                            continue;
                        }
                        String term = str(m.get("term"));
                        if (term.isBlank()) {
                            errors.add("term missing name");
                        } else if (!termNames.add(term.toLowerCase())) {
                            duplicates.add("term:" + term);
                        }
                        String id = str(m.get("sourceBlockId"));
                        if (!id.isBlank()) {
                            track(id, referenced, duplicates);
                            if (!knownBlockIds.contains(id)) {
                                warnings.add("unknown blockNodeId: " + id);
                            }
                        }
                    }
                }
            }
            default -> errors.add("unknown kind: " + kind);
        }

        if (!duplicates.isEmpty()) {
            warnings.add("duplicated source references: " + String.join(", ", duplicates));
        }
        Set<String> missing = new HashSet<>(knownBlockIds);
        missing.removeAll(referenced);
        if (!knownBlockIds.isEmpty() && referenced.isEmpty()) {
            warnings.add("no source block coverage");
        } else if (("segmentation".equals(kind) || "headings".equals(kind)) && !missing.isEmpty() && missing.size() == knownBlockIds.size()) {
            warnings.add("proposal references none of the source blocks");
        } else if ("segmentation".equals(kind) && !missing.isEmpty()) {
            warnings.add("segmentation missing coverage for " + missing.size() + " block(s)");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("referencedBlockCount", referenced.size());
        report.put("knownBlockCount", knownBlockIds.size());
        report.put("duplicateCount", duplicates.size());
        report.put("missingCoverageCount", missing.size());
        return finish(errors.isEmpty(), errors, warnings, report);
    }

    private static void track(String id, Set<String> referenced, Set<String> duplicates) {
        if (!referenced.add(id)) {
            duplicates.add(id);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static ValidationResult finish(boolean valid, List<String> errors, List<String> warnings, Map<String, Object> report) {
        Map<String, Object> full = new LinkedHashMap<>(report);
        full.put("valid", valid);
        full.put("errors", List.copyOf(errors));
        full.put("warnings", List.copyOf(warnings));
        return new ValidationResult(valid, List.copyOf(errors), List.copyOf(warnings), full);
    }
}
