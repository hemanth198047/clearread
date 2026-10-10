package com.clearread.documents;

import java.util.UUID;

public record DocumentRecord(
        UUID id,
        UUID tenantId,
        String title,
        String formatId,
        String status,
        String originalObjectKey,
        String originalSha256,
        Long originalByteLength,
        String originalFilename,
        UUID createdBy,
        UUID currentRevisionId
) {
}
