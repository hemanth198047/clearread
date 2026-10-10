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

/** Conservative readable view for .ini / .conf / .properties / .env / extensionless text. */
@Component
public class ConfigAdapter implements FormatAdapter {

    private static final String GENERATOR = "config-text@1.0.0";

    @Override
    public String formatId() {
        return "config";
    }

    @Override
    public String adapterId() {
        return "config-text";
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
        warnings.add(new Warning("CONSERVATIVE_VIEW", "Displayed as structured key/value where possible; never executed", null));

        String headingId = AdapterSupport.nextId(seq, "h");
        body.add(ClearDocModels.heading(1, headingId, List.of(ClearDocModels.text(titleHint != null ? titleHint : "Config", null)), prov));

        String[] lines = text.replace("\r\n", "\n").split("\n");
        List<Map<String, Object>> items = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            int colon = trimmed.indexOf(':');
            int sep = eq >= 0 ? eq : colon;
            if (sep > 0) {
                String key = trimmed.substring(0, sep).trim();
                String value = trimmed.substring(sep + 1).trim();
                items.add(ClearDocModels.listItem(List.of(
                        ClearDocModels.paragraph(AdapterSupport.nextId(seq, "p"), List.of(
                                ClearDocModels.text(key + " = ", List.of("bold")),
                                ClearDocModels.text(maskIfSecret(key, value), null)
                        ), prov)
                ), null));
            }
        }
        if (!items.isEmpty()) {
            body.add(ClearDocModels.bulletList(AdapterSupport.nextId(seq, "kv"), items, prov));
        }
        body.add(ClearDocModels.formattedSource(AdapterSupport.nextId(seq, "src"), "ini", text, prov));

        List<OutlineItem> outline = List.of(new OutlineItem(1, titleHint != null ? titleHint : "Config", headingId));
        return AdapterSupport.build(
                tenantId, documentId, revisionId, formatId(), adapterId(), adapterVersion(),
                titleHint, originalFilename, contentSha256, content.length, body, outline, warnings
        );
    }

    private static String maskIfSecret(String key, String value) {
        String k = key.toLowerCase();
        if (k.contains("password") || k.contains("secret") || k.contains("token") || k.endsWith("key") || k.contains("api_key")) {
            return value.isEmpty() ? "" : "••••••";
        }
        return value;
    }
}
