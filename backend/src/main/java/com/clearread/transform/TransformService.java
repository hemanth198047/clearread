package com.clearread.transform;

import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TransformService {

    private final Map<String, FormatAdapter> adapters;

    public TransformService(List<FormatAdapter> adapterList) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(FormatAdapter::formatId, Function.identity()));
    }

    public Optional<FormatAdapter> findAdapter(String formatId) {
        return Optional.ofNullable(adapters.get(formatId));
    }

    public String detectFormat(String filename, String contentType) {
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")
                || lowerType.contains("markdown")) {
            return "markdown";
        }
        if (lowerName.endsWith(".txt") || lowerName.endsWith(".text")
                || lowerType.startsWith("text/plain")) {
            return "plain-text";
        }
        if (lowerType.startsWith("text/")) {
            return "plain-text";
        }
        return null;
    }

    public ClearDoc parse(
            String formatId,
            UUID tenantId,
            UUID documentId,
            UUID revisionId,
            String titleHint,
            String originalFilename,
            String contentSha256,
            byte[] content
    ) {
        FormatAdapter adapter = findAdapter(formatId)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported format: " + formatId));
        return adapter.parse(tenantId, documentId, revisionId, titleHint, originalFilename, contentSha256, content);
    }
}
