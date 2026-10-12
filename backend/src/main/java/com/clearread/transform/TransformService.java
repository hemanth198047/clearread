package com.clearread.transform;

import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TransformService {

    private static final Set<String> SOURCE_EXTS = Set.of(
            "java", "ts", "tsx", "js", "jsx", "mjs", "cjs", "py", "go", "rs", "rb", "kt", "kts",
            "cs", "c", "h", "cpp", "cc", "hpp", "cxx", "swift", "php", "sh", "bash", "sql",
            "css", "scala", "r", "lua", "pl", "pm"
    );

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
        String ext = extension(lowerName);

        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown") || lowerType.contains("markdown")) {
            return "markdown";
        }
        if ("json".equals(ext) || lowerType.contains("application/json") || lowerType.contains("+json")) {
            return "json";
        }
        if ("yaml".equals(ext) || "yml".equals(ext) || lowerType.contains("yaml") || lowerType.contains("yml")) {
            return "yaml";
        }
        if ("xml".equals(ext) || lowerType.contains("application/xml") || lowerType.contains("text/xml")) {
            return "xml";
        }
        if ("csv".equals(ext) || lowerType.contains("text/csv")) {
            return "csv";
        }
        if ("tsv".equals(ext) || lowerType.contains("tab-separated")) {
            return "tsv";
        }
        if ("log".equals(ext)) {
            return "log";
        }
        if ("ini".equals(ext) || "conf".equals(ext) || "properties".equals(ext) || "env".equals(ext)
                || lowerType.contains("text/x-java-properties")) {
            return "config";
        }
        if ("html".equals(ext) || "htm".equals(ext) || lowerType.contains("text/html")) {
            return "html";
        }
        if ("adoc".equals(ext) || "asciidoc".equals(ext) || "asc".equals(ext)
                || lowerType.contains("asciidoc")) {
            return "asciidoc";
        }
        if ("rst".equals(ext) || "rest".equals(ext) || lowerType.contains("x-rst") || lowerType.contains("restructuredtext")) {
            return "rst";
        }
        if (SOURCE_EXTS.contains(ext)) {
            return "source-code";
        }
        if (lowerName.endsWith(".txt") || lowerName.endsWith(".text") || lowerType.startsWith("text/plain")) {
            return "plain-text";
        }
        // Extensionless text-like content
        if ((filename != null && !filename.contains(".")) && (lowerType.startsWith("text/") || lowerType.isEmpty())) {
            return "config";
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

    private static String extension(String lowerName) {
        int slash = Math.max(lowerName.lastIndexOf('/'), lowerName.lastIndexOf('\\'));
        String base = slash >= 0 ? lowerName.substring(slash + 1) : lowerName;
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1) : "";
    }
}
