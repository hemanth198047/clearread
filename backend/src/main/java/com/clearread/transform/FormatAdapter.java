package com.clearread.transform;

import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;

import java.util.UUID;

public interface FormatAdapter {

    String formatId();

    String adapterId();

    String adapterVersion();

    ClearDoc parse(
            UUID tenantId,
            UUID documentId,
            UUID revisionId,
            String titleHint,
            String originalFilename,
            String contentSha256,
            byte[] content
    );
}
