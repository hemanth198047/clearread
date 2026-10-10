package com.clearread.transform;

import com.clearread.transform.cleardoc.ClearDocModels;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.Metadata;
import com.clearread.transform.cleardoc.ClearDocModels.OutlineItem;
import com.clearread.transform.cleardoc.ClearDocModels.Provenance;
import com.clearread.transform.cleardoc.ClearDocModels.Source;
import com.clearread.transform.cleardoc.ClearDocModels.SourceRange;
import com.clearread.transform.cleardoc.ClearDocModels.Warning;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Component
public class PlainTextAdapter implements FormatAdapter {

    private static final String GENERATOR = "plain-text-heuristic@1.0.0";
    private static final Pattern ALL_CAPS = Pattern.compile("^[A-Z0-9][A-Z0-9 ,.'\\-]{2,80}$");

    @Override
    public String formatId() {
        return "plain-text";
    }

    @Override
    public String adapterId() {
        return "plain-text-heuristic";
    }

    @Override
    public String adapterVersion() {
        return "1.0.0";
    }

    @Override
    public ClearDoc parse(
            UUID tenantId,
            UUID documentId,
            UUID revisionId,
            String titleHint,
            String originalFilename,
            String contentSha256,
            byte[] content
    ) {
        String text = new String(content, StandardCharsets.UTF_8);
        String[] paragraphs = text.replace("\r\n", "\n").split("\\n\\s*\\n");
        AtomicInteger seq = new AtomicInteger();
        List<Map<String, Object>> body = ClearDocModels.mutableBody();
        List<OutlineItem> outline = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        int offset = 0;

        for (String raw : paragraphs) {
            String block = raw.strip();
            if (block.isEmpty()) {
                continue;
            }
            int start = text.indexOf(block, offset);
            if (start < 0) {
                start = offset;
            }
            int end = start + block.length();
            offset = end;
            String nodeId = "n_" + seq.incrementAndGet();
            boolean looksLikeHeading = block.indexOf('\n') < 0 && ALL_CAPS.matcher(block).matches();
            if (looksLikeHeading) {
                Provenance provenance = new Provenance("inferred", GENERATOR, new SourceRange(start, end), 0.6);
                body.add(ClearDocModels.heading(2, nodeId, List.of(ClearDocModels.text(block, null)), provenance));
                outline.add(new OutlineItem(2, block, nodeId));
                warnings.add(new Warning("INFERRED_HEADING", "Heading inferred from ALL-CAPS line", nodeId));
            } else {
                Provenance provenance = new Provenance("inferred", GENERATOR, new SourceRange(start, end), 0.9);
                List<Map<String, Object>> children = List.of(ClearDocModels.text(block, null));
                body.add(ClearDocModels.paragraph(nodeId, children, provenance));
            }
        }

        if (body.isEmpty()) {
            String nodeId = "n_1";
            body.add(ClearDocModels.paragraph(nodeId, List.of(ClearDocModels.text("", null)),
                    new Provenance("inferred", GENERATOR, new SourceRange(0, 0), 1.0)));
        }

        String title = titleHint != null && !titleHint.isBlank() ? titleHint
                : (originalFilename != null ? originalFilename : "Untitled");

        return new ClearDoc(
                "1.0.0",
                documentId.toString(),
                revisionId.toString(),
                tenantId.toString(),
                new Source(formatId(), adapterId(), adapterVersion(), contentSha256, originalFilename, content.length),
                new Metadata(title, "en", Map.of()),
                outline,
                warnings,
                body
        );
    }
}
