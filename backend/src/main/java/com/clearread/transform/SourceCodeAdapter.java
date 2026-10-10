package com.clearread.transform;

import com.clearread.transform.cleardoc.ClearDocModels;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.OutlineItem;
import com.clearread.transform.cleardoc.ClearDocModels.Provenance;
import com.clearread.transform.cleardoc.ClearDocModels.Warning;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Readable source viewer — syntax language hint only; never executes. */
@Component
public class SourceCodeAdapter implements FormatAdapter {

    private static final String GENERATOR = "source-code@1.0.0";

    @Override
    public String formatId() {
        return "source-code";
    }

    @Override
    public String adapterId() {
        return "source-code";
    }

    @Override
    public String adapterVersion() {
        return "1.0.0";
    }

    @Override
    public ClearDoc parse(
            UUID tenantId, UUID documentId, UUID revisionId, String titleHint,
            String originalFilename, String contentSha256, byte[] content
    ) {
        String text = new String(content, StandardCharsets.UTF_8);
        AtomicInteger seq = new AtomicInteger();
        Provenance prov = AdapterSupport.sourceProv(GENERATOR);
        List<Map<String, Object>> body = ClearDocModels.mutableBody();
        List<Warning> warnings = new ArrayList<>();
        warnings.add(new Warning("NEVER_EXECUTE", "Source is displayed for reading only and is never executed", null));

        String language = languageFromFilename(originalFilename);
        String headingId = AdapterSupport.nextId(seq, "h");
        String title = titleHint != null ? titleHint : (originalFilename != null ? originalFilename : "Source");
        body.add(ClearDocModels.heading(1, headingId, List.of(ClearDocModels.text(title, null)), prov));
        body.add(ClearDocModels.codeBlock(AdapterSupport.nextId(seq, "code"), language, text, prov));

        List<OutlineItem> outline = List.of(new OutlineItem(1, title, headingId));
        return AdapterSupport.build(
                tenantId, documentId, revisionId, formatId(), adapterId(), adapterVersion(),
                titleHint, originalFilename, contentSha256, content.length, body, outline, warnings
        );
    }

    static String languageFromFilename(String filename) {
        if (filename == null) {
            return "text";
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return "text";
        }
        String ext = lower.substring(dot + 1);
        return switch (ext) {
            case "java" -> "java";
            case "ts", "tsx" -> "typescript";
            case "js", "jsx", "mjs", "cjs" -> "javascript";
            case "py" -> "python";
            case "go" -> "go";
            case "rs" -> "rust";
            case "rb" -> "ruby";
            case "kt", "kts" -> "kotlin";
            case "cs" -> "csharp";
            case "c", "h" -> "c";
            case "cpp", "cc", "hpp", "cxx" -> "cpp";
            case "swift" -> "swift";
            case "php" -> "php";
            case "sh", "bash" -> "bash";
            case "sql" -> "sql";
            case "css" -> "css";
            case "html", "htm" -> "html";
            case "scala" -> "scala";
            default -> ext;
        };
    }
}
