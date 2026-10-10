package com.clearread.jobs;

import com.clearread.capabilities.CapabilityService;
import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentRepository;
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

import java.util.UUID;

@Component
public class TransformJobWorker {

    private static final Logger log = LoggerFactory.getLogger(TransformJobWorker.class);

    private final JobRepository jobRepository;
    private final DocumentRepository documentRepository;
    private final ObjectStorageService storage;
    private final TransformService transformService;
    private final CapabilityService capabilityService;
    private final ObjectMapper objectMapper;
    private final com.clearread.config.ClearReadProperties properties;

    public TransformJobWorker(
            JobRepository jobRepository,
            DocumentRepository documentRepository,
            ObjectStorageService storage,
            TransformService transformService,
            CapabilityService capabilityService,
            ObjectMapper objectMapper,
            com.clearread.config.ClearReadProperties properties
    ) {
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.storage = storage;
        this.transformService = transformService;
        this.capabilityService = capabilityService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${clearread.jobs.poll-interval-ms:1000}")
    public void poll() {
        var jobs = jobRepository.claim(
                properties.getJobs().getWorkerId(),
                properties.getJobs().getLeaseSeconds(),
                5
        );
        for (JobRepository.JobRecord job : jobs) {
            try {
                process(job);
                jobRepository.succeed(job.id());
            } catch (Exception ex) {
                log.warn("Job {} failed: {}", job.id(), ex.getMessage());
                jobRepository.fail(job.id(), ex.getMessage(), job.attempt(), job.maxAttempts());
                try {
                    JsonNode payload = objectMapper.readTree(job.payloadJson());
                    UUID documentId = UUID.fromString(payload.path("documentId").asText());
                    documentRepository.markFailed(job.tenantId(), documentId);
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
    }

    private void process(JobRepository.JobRecord job) throws Exception {
        if (!"transform.document".equals(job.type())) {
            throw new IllegalArgumentException("Unknown job type: " + job.type());
        }
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
                1,
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
