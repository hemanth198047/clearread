package com.clearread.jobs;

import com.clearread.capabilities.CapabilityService;
import com.clearread.config.ClearReadProperties;
import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentRepository;
import com.clearread.net.SafeHttpFetcher;
import com.clearread.net.UrlAccessPolicy;
import com.clearread.net.UrlLogRedactor;
import com.clearread.storage.ObjectStorageService;
import com.clearread.transform.FormatAdapter;
import com.clearread.transform.TransformService;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-process durable job worker (D-JOB-2). Long-running fetch/transform work runs here,
 * not on HTTP request threads. Separate worker process remains an evolution seam.
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRepository jobRepository;
    private final DocumentRepository documentRepository;
    private final ObjectStorageService storage;
    private final TransformService transformService;
    private final CapabilityService capabilityService;
    private final ObjectMapper objectMapper;
    private final ClearReadProperties properties;
    private final SafeHttpFetcher fetcher;

    public JobWorker(
            JobRepository jobRepository,
            DocumentRepository documentRepository,
            ObjectStorageService storage,
            TransformService transformService,
            CapabilityService capabilityService,
            ObjectMapper objectMapper,
            ClearReadProperties properties,
            SafeHttpFetcher fetcher
    ) {
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.storage = storage;
        this.transformService = transformService;
        this.capabilityService = capabilityService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.fetcher = fetcher;
    }

    @Scheduled(fixedDelayString = "${clearread.jobs.poll-interval-ms:1000}")
    public void poll() {
        if (!properties.getJobs().isEnabled()) {
            return;
        }
        var jobs = jobRepository.claim(
                properties.getJobs().getWorkerId(),
                properties.getJobs().getLeaseSeconds(),
                properties.getJobs().getBatchSize(),
                properties.getJobs().getMaxConcurrentPerTenant()
        );
        for (JobRepository.JobRecord job : jobs) {
            try {
                if (job.cancelRequested()) {
                    jobRepository.markCancelled(job.id(), job.leaseToken());
                    continue;
                }
                process(job);
                jobRepository.succeed(job.id(), job.leaseToken());
            } catch (UrlAccessPolicy.Denied denied) {
                log.warn("Job {} policy denied: {}", job.id(), denied.getMessage());
                jobRepository.fail(job.id(), job.leaseToken(), denied.getMessage(), job.attempt(), job.maxAttempts());
                markDocFailed(job);
            } catch (Exception ex) {
                log.warn("Job {} failed: {}", job.id(), ex.getMessage());
                jobRepository.fail(job.id(), job.leaseToken(), ex.getMessage(), job.attempt(), job.maxAttempts());
                markDocFailed(job);
            }
        }
    }

    private void markDocFailed(JobRepository.JobRecord job) {
        try {
            JsonNode payload = objectMapper.readTree(job.payloadJson());
            UUID documentId = UUID.fromString(payload.path("documentId").asText());
            documentRepository.markFailed(job.tenantId(), documentId);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private void process(JobRepository.JobRecord job) throws Exception {
        switch (job.type()) {
            case "ingest.fetch" -> processFetch(job);
            case "transform.document" -> processTransform(job);
            default -> throw new IllegalArgumentException("Unknown job type: " + job.type());
        }
    }

    private void processFetch(JobRepository.JobRecord job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.payloadJson());
        UUID documentId = UUID.fromString(payload.path("documentId").asText());
        String url = payload.path("url").asText();
        documentRepository.updateStatus(job.tenantId(), documentId, "fetching");

        SafeHttpFetcher.FetchResult result = fetcher.fetch(url);
        if (result.looksLikeWebPage()) {
            throw new UrlAccessPolicy.Denied(
                    "WEB_PAGE",
                    "This URL returns a web page, not a raw file. ClearRead does not scrape HTML pages; "
                            + "provide a direct file URL (for example .json, .md, .csv) or paste/upload the content."
            );
        }

        String filename = result.filenameHint() != null ? result.filenameHint() : "download";
        String formatId = transformService.detectFormat(filename, result.contentType());
        if (formatId == null || !capabilityService.supportsIngest(formatId)) {
            throw new IllegalStateException("Unsupported format for URL content ("
                    + (result.contentType() == null ? "unknown type" : result.contentType()) + ")");
        }
        if (result.body().length > properties.getLimits().getMaxUploadBytes()) {
            throw new IllegalStateException("Fetched content exceeds max size");
        }

        String sha = ObjectStorageService.sha256(result.body());
        String key = storage.originalKey(job.tenantId(), documentId, sha);
        storage.putBytes(key, result.body(), result.contentType() != null ? result.contentType() : "application/octet-stream");

        Map<String, Object> meta = new LinkedHashMap<>(result.meta());
        meta.put("requestedUrl", UrlLogRedactor.redact(url));
        String metaJson = objectMapper.writeValueAsString(meta);

        documentRepository.updateSourceFetched(
                job.tenantId(), documentId, key, sha, result.body().length,
                result.contentType(), filename, metaJson
        );
        documentRepository.attachOriginal(
                job.tenantId(), documentId, key, sha, result.body().length, filename, formatId
        );
        documentRepository.updateStatus(job.tenantId(), documentId, "queued");

        Map<String, Object> transformPayload = new LinkedHashMap<>();
        transformPayload.put("documentId", documentId.toString());
        jobRepository.enqueue(
                UUID.randomUUID(),
                job.tenantId(),
                "transform.document",
                objectMapper.writeValueAsString(transformPayload),
                "transform:" + documentId
        );
    }

    private void processTransform(JobRepository.JobRecord job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.payloadJson());
        UUID documentId = UUID.fromString(payload.path("documentId").asText());
        DocumentRecord doc = documentRepository.findByTenantAndId(job.tenantId(), documentId)
                .orElseThrow(() -> new IllegalStateException("Document not found"));

        documentRepository.updateStatus(job.tenantId(), documentId, "processing");
        byte[] original = storage.getBytes(doc.originalObjectKey());
        String sha = ObjectStorageService.sha256(original);
        if (doc.originalSha256() != null && !doc.originalSha256().equalsIgnoreCase(sha)) {
            throw new IllegalStateException("Original checksum mismatch");
        }

        UUID revisionId = UUID.randomUUID();
        int revisionNumber = documentRepository.nextRevisionNumber(job.tenantId(), documentId);
        ClearDoc cleardoc = transformService.parse(
                doc.formatId(),
                job.tenantId(),
                documentId,
                revisionId,
                doc.title(),
                doc.originalFilename(),
                sha,
                original
        );
        FormatAdapter adapter = transformService.findAdapter(doc.formatId()).orElseThrow();
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(cleardoc);
        String key = storage.cleardocKey(job.tenantId(), documentId, revisionId);
        var stored = storage.putBytes(key, json, "application/json");

        documentRepository.insertRevision(
                revisionId,
                job.tenantId(),
                documentId,
                revisionNumber,
                stored.key(),
                stored.sha256(),
                cleardoc.schemaVersion(),
                adapter.adapterId(),
                adapter.adapterVersion(),
                capabilityService.registryVersion(),
                objectMapper.writeValueAsString(cleardoc.warnings())
        );
        documentRepository.markReady(job.tenantId(), documentId, revisionId);
    }
}
