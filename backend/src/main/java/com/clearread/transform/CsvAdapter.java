package com.clearread.transform;

import com.clearread.config.ClearReadProperties;
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

@Component
public class CsvAdapter implements FormatAdapter {

    private static final String GENERATOR = "csv-table@1.0.0";

    private final int maxRows;

    public CsvAdapter(ClearReadProperties properties) {
        this.maxRows = properties.getLimits().getMaxCsvRows();
    }

    @Override
    public String formatId() {
        return "csv";
    }

    @Override
    public String adapterId() {
        return "csv-table";
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
        return parseDelimited(tenantId, documentId, revisionId, titleHint, originalFilename, contentSha256, content,
                "csv", ',', maxRows);
    }

    static ClearDoc parseDelimited(
            UUID tenantId, UUID documentId, UUID revisionId, String titleHint,
            String originalFilename, String contentSha256, byte[] content,
            String formatId, char delimiter, int maxRows
    ) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<Warning> warnings = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        Provenance prov = AdapterSupport.sourceProv(GENERATOR);
        List<Map<String, Object>> body = ClearDocModels.mutableBody();
        String headingId = AdapterSupport.nextId(seq, "h");
        body.add(ClearDocModels.heading(1, headingId, List.of(ClearDocModels.text(titleHint != null ? titleHint : formatId.toUpperCase(), null)), prov));

        List<List<String>> records = parseCsv(text, delimiter);
        if (records.size() > maxRows + 1) {
            warnings.add(new Warning("CSV_TRUNCATED", "Showing first " + maxRows + " data rows", null));
            records = records.subList(0, maxRows + 1);
        }
        boolean headerRow = !records.isEmpty();
        List<String> headers = headerRow ? records.getFirst() : List.of();
        List<List<String>> rows = headerRow && records.size() > 1 ? records.subList(1, records.size()) : List.of();
        body.add(ClearDocModels.dataTable(
                AdapterSupport.nextId(seq, "t"),
                headers,
                rows,
                String.valueOf(delimiter),
                headerRow,
                prov
        ));
        body.add(ClearDocModels.formattedSource(AdapterSupport.nextId(seq, "src"), formatId, text, prov));

        List<OutlineItem> outline = List.of(new OutlineItem(1, titleHint != null ? titleHint : formatId.toUpperCase(), headingId));
        return AdapterSupport.build(
                tenantId, documentId, revisionId, formatId, formatId + "-table", "1.0.0",
                titleHint, originalFilename, contentSha256, content.length, body, outline, warnings
        );
    }

    /** RFC4180-ish parser with quoted fields and multiline values. */
    static List<List<String>> parseCsv(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c == '\r') {
                // skip
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
