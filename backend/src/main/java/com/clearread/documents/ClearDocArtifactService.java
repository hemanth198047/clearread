package com.clearread.documents;

import com.clearread.storage.ObjectStorageService;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads ClearDoc JSON from object storage with recoverable HTTP statuses.
 * Missing/corrupt artifacts must not surface as opaque 500s — callers can Reprocess.
 */
@Service
public class ClearDocArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ClearDocArtifactService.class);

    static final String MISSING_REASON =
            "ClearDoc artifact missing from storage — use Reprocess to rebuild from the original";
    static final String CORRUPT_REASON =
            "ClearDoc artifact corrupt or unreadable — use Reprocess to rebuild from the original";
    static final String STORAGE_REASON = "Failed to fetch ClearDoc from storage";

    private final ObjectStorageService storage;
    private final ObjectMapper objectMapper;

    public ClearDocArtifactService(ObjectStorageService storage, ObjectMapper objectMapper) {
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    public ClearDoc read(String objectKey) {
        return read(objectKey, null);
    }

    public ClearDoc read(String objectKey, java.util.UUID documentId) {
        byte[] bytes;
        try {
            bytes = storage.getBytes(objectKey);
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            log.warn(
                    "ClearDoc object missing documentId={} key={}: {}",
                    documentId, objectKey, e.toString()
            );
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, MISSING_REASON, e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                log.warn(
                        "ClearDoc object not found (S3 404) documentId={} key={}: {}",
                        documentId, objectKey, e.toString()
                );
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, MISSING_REASON, e);
            }
            log.error(
                    "S3 error reading ClearDoc documentId={} key={} status={}",
                    documentId, objectKey, e.statusCode(), e
            );
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, STORAGE_REASON, e);
        } catch (RuntimeException e) {
            // LocalStack / SDK wrappers sometimes surface 404 as generic RuntimeException
            if (looksLikeMissingObject(e)) {
                log.warn(
                        "ClearDoc object missing (wrapped) documentId={} key={}: {}",
                        documentId, objectKey, e.toString()
                );
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, MISSING_REASON, e);
            }
            log.error("Unexpected storage error reading ClearDoc documentId={} key={}", documentId, objectKey, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, STORAGE_REASON, e);
        }

        if (bytes == null || bytes.length == 0) {
            log.warn("ClearDoc object empty documentId={} key={}", documentId, objectKey);
            throw new ResponseStatusException(HttpStatus.CONFLICT, CORRUPT_REASON);
        }

        try {
            return objectMapper.readValue(bytes, ClearDoc.class);
        } catch (Exception e) {
            log.error(
                    "ClearDoc JSON unreadable documentId={} key={} bytes={}",
                    documentId, objectKey, bytes.length, e
            );
            throw new ResponseStatusException(HttpStatus.CONFLICT, CORRUPT_REASON, e);
        }
    }

    static boolean looksLikeMissingObject(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NoSuchKeyException || t instanceof NoSuchBucketException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("nosuchkey")
                        || lower.contains("not found")
                        || lower.contains("no such key")
                        || lower.contains("404")) {
                    return true;
                }
            }
        }
        return false;
    }
}
