package com.clearread.storage;

import com.clearread.config.ClearReadProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ObjectStorageService {

    private final S3Client s3;
    private final ClearReadProperties properties;

    public ObjectStorageService(S3Client s3, ClearReadProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @PostConstruct
    void ensureBucket() {
        String bucket = properties.getStorage().getBucket();
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (Exception ex) {
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (Exception createEx) {
                // Storage may not be up during unit-only tests; API readiness covers live deps.
            }
        }
    }

    public String originalKey(UUID tenantId, UUID documentId, String sha256) {
        return "tenants/%s/documents/%s/original/%s".formatted(tenantId, documentId, sha256);
    }

    public String cleardocKey(UUID tenantId, UUID documentId, UUID revisionId) {
        return "tenants/%s/documents/%s/revisions/%s/cleardoc.json".formatted(tenantId, documentId, revisionId);
    }

    public StoredObject putBytes(String key, byte[] bytes, String contentType) {
        String sha = sha256(bytes);
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getStorage().getBucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes)
        );
        return new StoredObject(key, sha, bytes.length);
    }

    public byte[] getBytes(String key) {
        ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(properties.getStorage().getBucket())
                        .key(key)
                        .build()
        );
        return response.asByteArray();
    }

    /** True when the object exists in the configured bucket. */
    public boolean exists(String key) {
        try {
            s3.headObject(
                    HeadObjectRequest.builder()
                            .bucket(properties.getStorage().getBucket())
                            .key(key)
                            .build()
            );
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public record StoredObject(String key, String sha256, long byteLength) {
    }
}
