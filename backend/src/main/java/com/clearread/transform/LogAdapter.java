package com.clearread.transform;

import com.clearread.transform.cleardoc.ClearDocModels;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.OutlineItem;
import com.clearread.transform.cleardoc.ClearDocModels.Provenance;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Conservative line-oriented view for .log files — never executes content. */
@Component
public class LogAdapter implements FormatAdapter {

    private static final String GENERATOR = "log-lines@1.0.0";
    private static final int MAX_LINES = 20_000;

    @Override
    public String formatId() {
        return "log";
    }

    @Override
    public String adapterId() {
        return "log-lines";
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
        String headingId = AdapterSupport.nextId(seq, "h");
        body.add(ClearDocModels.heading(1, headingId, List.of(ClearDocModels.text(titleHint != null ? titleHint : "Log", null)), prov));

        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        StringBuilder shown = new StringBuilder();
        int limit = Math.min(lines.length, MAX_LINES);
        for (int i = 0; i < limit; i++) {
            shown.append(lines[i]).append('\n');
        }
        List<com.clearread.transform.cleardoc.ClearDocModels.Warning> warnings = new ArrayList<>();
        if (lines.length > MAX_LINES) {
            warnings.add(new com.clearread.transform.cleardoc.ClearDocModels.Warning(
                    "LOG_TRUNCATED", "Showing first " + MAX_LINES + " lines", null));
        }
        body.add(ClearDocModels.codeBlock(AdapterSupport.nextId(seq, "log"), "log", shown.toString(), prov));

        List<OutlineItem> outline = List.of(new OutlineItem(1, titleHint != null ? titleHint : "Log", headingId));
        return AdapterSupport.build(
                tenantId, documentId, revisionId, formatId(), adapterId(), adapterVersion(),
                titleHint, originalFilename, contentSha256, content.length, body, outline, warnings
        );
    }
}
