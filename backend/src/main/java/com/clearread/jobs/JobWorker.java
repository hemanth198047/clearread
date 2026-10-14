package com.clearread.jobs;

import com.clearread.ai.EnrichmentService;
import com.clearread.annotations.AnnotationService;
import com.clearread.capabilities.CapabilityService;
import com.clearread.config.ClearReadProperties;
import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentRepository;
import com.clearread.net.SafeHttpFetcher;
import com.clearread.net.UrlAccessPolicy;
import com.clearread.net.UrlLogRedactor;
import com.clearread.search.SearchService;
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
import java.util.List;
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
    private final SearchService searchService;
    private final AnnotationService annotationService;
    private final EnrichmentService enrichmentService;

    public JobWorker(
            JobRepository jobRepository,
            DocumentRepository documentRepository,
            ObjectStorageService storage,
            TransformService transformService,
            CapabilityService capabilityService,
            ObjectMapper objectMapper,
            ClearReadProperties properties,
            SafeHttpFetcher fetcher,
            SearchService searchService,
            AnnotationService annotationService,
            EnrichmentService enrichmentService
    ) {
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.storage = storage;
        this.transformService = transformService;
        this.capabilityService = capabilityService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.fetcher = fetcher;
        this.searchService = searchService;
        this.annotationService = annotationService;
        this.enrichmentService = enrichmentService;
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
                markDocFailedIfIngest(job);
            } catch (Exception ex) {
                log.warn("Job {} failed: {}", job.id(), ex.getMessage());
                jobRepository.fail(job.id(), job.leaseToken(), ex.getMessage(), job.attempt(), job.maxAttempts());
                markDocFailedIfIngest(job);
            }
        }
    }

    private void markDocFailedIfIngest(JobRepository.JobRecord job) {
        if ("enrich.document".equals(job.type())) {
            return; // enrichment failure must not break the normal reader path
        }
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
            case "enrich.document" -> processEnrich(job);
            default -> throw new IllegalArgumentException("Unknown job type: " + job.type());
        }
    }

    private void processEnrich(JobRepository.JobRecord job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.payloadJson());
        UUID enrichmentId = UUID.fromString(payload.path("enrichmentId").asText());
        enrichmentService.processJob(job.tenantId(), enrichmentId);
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
        if (doc.originalObjectKey() == null || !storage.exists(doc.originalObjectKey())) {
            documentRepository.softDelete(job.tenantId(), documentId);
            throw new IllegalStateException(
                    "Original object missing from storage — document removed from library"
            );
        }
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
        // Confirm the artifact is readable before marking the document ready (avoids ready+404 races).
        if (!storage.exists(stored.key())) {
            throw new IllegalStateException("ClearDoc write did not persist to object storage");
        }

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
        if (revisionNumber > 1) {
            annotationService.markUncertainForDocument(job.tenantId(), documentId);
        }
        searchService.upsertIndex(
                job.tenantId(),
                documentId,
                revisionId,
                cleardoc.metadata() != null ? cleardoc.metadata().title() : doc.title(),
                extractSearchText(cleardoc)
        );
    }

    private static String extractSearchText(ClearDoc doc) {
        StringBuilder sb = new StringBuilder();
        walk(doc.body(), sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        if (node instanceof String s) {
            sb.append(s).append(' ');
            return;
        }
        if (node instanceof List<?> list) {
            for (Object o : list) {
                walk(o, sb);
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            Object value = map.get("value");
            Object text = map.get("text");
            Object label = map.get("label");
            Object preview = map.get("valuePreview");
            if (value instanceof String) {
                sb.append(value).append(' ');
            }
            if (text instanceof String) {
                sb.append(text).append(' ');
            }
            if (label instanceof String) {
                sb.append(label).append(' ');
            }
            if (preview instanceof String) {
                sb.append(preview).append(' ');
            }
            walk(map.get("children"), sb);
            walk(map.get("items"), sb);
            walk(map.get("headers"), sb);
            walk(map.get("rows"), sb);
            walk(map.get("body"), sb);
        }
    }
}
