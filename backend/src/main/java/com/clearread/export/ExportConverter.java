package com.clearread.export;

import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;

/**
 * Converts ClearDoc (derived IR) into readable formats. Outputs are generated artifacts,
 * never a substitute for the immutable original.
 */
public interface ExportConverter {

    String targetFormatId();

    String converterId();

    String converterVersion();

    ExportResult convert(ClearDoc doc);

    record ExportResult(byte[] bytes, String contentType, String filenameSuffix) {
    }
}
