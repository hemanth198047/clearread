package com.clearread.transform;

import com.clearread.config.ClearReadProperties;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TsvAdapter implements FormatAdapter {

    private final int maxRows;

    public TsvAdapter(ClearReadProperties properties) {
        this.maxRows = properties.getLimits().getMaxCsvRows();
    }

    @Override
    public String formatId() {
        return "tsv";
    }

    @Override
    public String adapterId() {
        return "tsv-table";
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
        return CsvAdapter.parseDelimited(
                tenantId, documentId, revisionId, titleHint, originalFilename, contentSha256, content,
                "tsv", '\t', maxRows
        );
    }
}
