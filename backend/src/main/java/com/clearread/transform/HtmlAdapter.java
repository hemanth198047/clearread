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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative sanitized HTML ingest for uploaded .html files.
 * Strips scripts/styles/event handlers; does not fetch remote resources.
 */
@Component
public class HtmlAdapter implements FormatAdapter {

    private static final String GENERATOR = "html-sanitize@1.0.0";
    private static final Pattern TAG = Pattern.compile("(?is)<(/)?([a-z0-9]+)([^>]*)>");
    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b[^>]*>.*?</script>");
    private static final Pattern STYLE = Pattern.compile("(?is)<style\\b[^>]*>.*?</style>");

    @Override
    public String formatId() {
        return "html";
    }

    @Override
    public String adapterId() {
        return "html-sanitize";
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
        String raw = new String(content, StandardCharsets.UTF_8);
        AtomicInteger seq = new AtomicInteger();
        Provenance prov = AdapterSupport.sourceProv(GENERATOR);
        List<Map<String, Object>> body = ClearDocModels.mutableBody();
        List<Warning> warnings = new ArrayList<>();
        warnings.add(new Warning("HTML_SANITIZED", "Scripts, styles, and event handlers were stripped; remote resources are not fetched", null));

        String headingId = AdapterSupport.nextId(seq, "h");
        String title = titleHint != null ? titleHint : extractTitle(raw, originalFilename);
        body.add(ClearDocModels.heading(1, headingId, List.of(ClearDocModels.text(title, null)), prov));

        String cleaned = STYLE.matcher(SCRIPT.matcher(raw).replaceAll("")).replaceAll("");
        cleaned = cleaned.replaceAll("(?is)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        cleaned = cleaned.replaceAll("(?is)javascript:", "");

        Matcher m = TAG.matcher(cleaned);
        StringBuilder textBuf = new StringBuilder();
        int last = 0;
        while (m.find()) {
            textBuf.append(cleaned, last, m.start());
            last = m.end();
            boolean close = m.group(1) != null;
            String tag = m.group(2).toLowerCase();
            if (!close && (tag.equals("p") || tag.equals("div") || tag.equals("br") || tag.equals("li")
                    || tag.matches("h[1-6]"))) {
                flushParagraph(body, textBuf, seq, prov, tag.startsWith("h") ? Integer.parseInt(tag.substring(1)) : 0);
            }
        }
        textBuf.append(cleaned.substring(last));
        flushParagraph(body, textBuf, seq, prov, 0);
        body.add(ClearDocModels.formattedSource(AdapterSupport.nextId(seq, "src"), "html", cleaned, prov));

        List<OutlineItem> outline = AdapterSupport.outlineFrom(body);
        if (outline.isEmpty()) {
            outline = List.of(new OutlineItem(1, title, headingId));
        }
        return AdapterSupport.build(
                tenantId, documentId, revisionId, formatId(), adapterId(), adapterVersion(),
                titleHint, originalFilename, contentSha256, content.length, body, outline, warnings
        );
    }

    private static void flushParagraph(
            List<Map<String, Object>> body, StringBuilder textBuf, AtomicInteger seq, Provenance prov, int headingLevel
    ) {
        String text = textBuf.toString().replaceAll("\\s+", " ").trim();
        textBuf.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        if (headingLevel >= 1 && headingLevel <= 6) {
            body.add(ClearDocModels.heading(headingLevel, AdapterSupport.nextId(seq, "h"),
                    List.of(ClearDocModels.text(text, null)), prov));
        } else {
            body.add(ClearDocModels.paragraph(AdapterSupport.nextId(seq, "p"),
                    List.of(ClearDocModels.text(text, null)), prov));
        }
    }

    private static String extractTitle(String raw, String filename) {
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(raw);
        if (m.find()) {
            return m.group(1).replaceAll("\\s+", " ").trim();
        }
        return filename != null ? filename : "HTML";
    }
}
