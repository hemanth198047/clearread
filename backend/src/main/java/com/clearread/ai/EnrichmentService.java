package com.clearread.ai;

import com.clearread.audit.AuditService;
import com.clearread.config.ClearReadProperties;
import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentRepository;
import com.clearread.documents.DocumentService;
import com.clearread.identity.UserRecord;
import com.clearread.jobs.JobRepository;
import com.clearread.storage.ObjectStorageService;
import com.clearread.tenancy.TenantContext;
import com.clearread.tenancy.WorkspaceSettingsRepository;
import com.clearread.tenancy.WorkspaceSettingsRepository.WorkspaceSettings;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);
    private static final Set<String> KINDS = Set.of("headings", "segmentation", "summary", "glossary");

    private final EnrichmentRepository enrichments;
    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final ObjectStorageService storage;
    private final WorkspaceSettingsRepository settings;
    private final AiProviderRegistry providers;
    private final AiConnectionConfigService connectionConfig;
    private final JobRepository jobs;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final ClearReadProperties properties;

    public EnrichmentService(
            EnrichmentRepository enrichments,
            DocumentService documentService,
            DocumentRepository documentRepository,
            ObjectStorageService storage,
            WorkspaceSettingsRepository settings,
            AiProviderRegistry providers,
            AiConnectionConfigService connectionConfig,
            JobRepository jobs,
            AuditService audit,
            ObjectMapper objectMapper,
            ClearReadProperties properties
    ) {
        this.enrichments = enrichments;
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.storage = storage;
        this.settings = settings;
        this.providers = providers;
        this.connectionConfig = connectionConfig;
        this.jobs = jobs;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Map<String, Object> status(UserRecord user, UUID documentId) {
        DocumentRecord doc = documentService.getForRead(user, documentId);
        WorkspaceSettings ws = settings.getOrDefaults(doc.tenantId());
        return connectionConfig.withTenant(doc.tenantId(), () -> {
            AiProvider provider = providers.require(ws.aiProviderId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("aiEnabled", ws.aiEnabled());
            m.put("aiLocalOnly", ws.aiLocalOnly());
            m.put("aiAllowExternal", ws.aiAllowExternal());
            m.put("aiProviderId", ws.aiProviderId());
            m.put("aiDailyQuota", ws.aiDailyQuota());
            m.put("usageToday", enrichments.usageToday(doc.tenantId()));
            m.put("providers", providers.list());
            m.put("documentReady", "ready".equals(doc.status()));
            m.put("providerAvailable", provider.isAvailable());
            m.put("providerModelId", provider.modelId());
            m.put("providerLocal", provider.isLocal());
            m.put("connection", connectionConfig.toPublicMap(doc.tenantId()));
            m.put("configurePath", "/admin#ai-provider");
            return m;
        });
    }

    @Transactional
    public Map<String, Object> requestEnrichment(UserRecord user, UUID documentId, String kind) {
        requireAuthor();
        if (!KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid enrichment kind");
        }
        DocumentRecord doc = documentService.getForRead(user, documentId);
        if (!"ready".equals(doc.status()) || doc.currentRevisionId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Document not ready");
        }
        WorkspaceSettings ws = settings.getOrDefaults(doc.tenantId());
        if (!ws.aiEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "AI enrichment is disabled for this workspace (org policy)");
        }
        return connectionConfig.withTenant(doc.tenantId(), () -> {
            AiProvider provider = providers.require(ws.aiProviderId());
            if (!provider.isLocal() && (ws.aiLocalOnly() || !ws.aiAllowExternal())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "External AI is not permitted by workspace policy (enable allow-external and disable local-only)");
            }
            if (!provider.isAvailable()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "AI provider '" + provider.providerId() + "' is not available/configured");
            }
            if (enrichments.usageToday(doc.tenantId()) >= ws.aiDailyQuota()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Daily AI quota exceeded");
            }

            var template = enrichments.latestTemplate(kind)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Missing prompt template"));
            String configHash = sha256(ws.aiProviderId() + "|" + template.id() + "|" + template.version()
                    + "|" + ws.aiLocalOnly() + "|" + ws.aiAllowExternal()
                    + "|" + connectionConfig.configFingerprint(doc.tenantId()));
            String cacheKey = sha256(doc.id() + "|" + doc.currentRevisionId() + "|" + kind + "|" + configHash);

            var cached = enrichments.findCachedReady(doc.tenantId(), cacheKey);
            if (cached.isPresent()) {
                audit.record(user.id(), "ai.enrichment.cache_hit", "ai_enrichment", cached.get().id().toString(),
                        Map.of("kind", kind, "documentId", documentId.toString()));
                return toMap(cached.get());
            }

            UUID enrichmentId = UUID.randomUUID();
            enrichments.insertPending(new EnrichmentRepository.EnrichmentRecord(
                    enrichmentId, doc.tenantId(), doc.id(), doc.currentRevisionId(), kind, "pending",
                    provider.providerId(), provider.modelId(), template.id(), template.version(),
                    configHash, cacheKey, null, null, user.id(), null
            ));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("documentId", documentId.toString());
            payload.put("enrichmentId", enrichmentId.toString());
            payload.put("kind", kind);
            try {
                int maxAttempts = Math.max(1, Math.min(ws.aiMaxRetries() + 1, properties.getAi().getMaxRetries() + 1));
                jobs.enqueueWithMaxAttempts(
                        UUID.randomUUID(),
                        doc.tenantId(),
                        "enrich.document",
                        objectMapper.writeValueAsString(payload),
                        "enrich:" + enrichmentId,
                        maxAttempts
                );
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to enqueue enrichment");
            }
            audit.record(user.id(), "ai.enrichment.request", "ai_enrichment", enrichmentId.toString(),
                    Map.of("kind", kind, "provider", provider.providerId(), "documentId", documentId.toString()));
            return toMap(enrichments.find(doc.tenantId(), enrichmentId).orElseThrow());
        });
    }

    public List<Map<String, Object>> list(UserRecord user, UUID documentId) {
        DocumentRecord doc = documentService.getForRead(user, documentId);
        return enrichments.listForDocument(doc.tenantId(), doc.id()).stream().map(this::toMap).toList();
    }

    public Map<String, Object> get(UserRecord user, UUID documentId, UUID enrichmentId) {
        DocumentRecord doc = documentService.getForRead(user, documentId);
        return toMap(enrichments.find(doc.tenantId(), enrichmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Enrichment not found")));
    }

    @Transactional
    public Map<String, Object> accept(UserRecord user, UUID documentId, UUID enrichmentId) {
        requireAuthor();
        DocumentRecord doc = documentService.getForRead(user, documentId);
        if (!enrichments.accept(doc.tenantId(), enrichmentId, user.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Enrichment not ready to accept");
        }
        audit.record(user.id(), "ai.enrichment.accept", "ai_enrichment", enrichmentId.toString(),
                Map.of("documentId", documentId.toString()));
        return toMap(enrichments.find(doc.tenantId(), enrichmentId).orElseThrow());
    }

    @Transactional
    public Map<String, Object> reject(UserRecord user, UUID documentId, UUID enrichmentId) {
        requireAuthor();
        DocumentRecord doc = documentService.getForRead(user, documentId);
        if (!enrichments.reject(doc.tenantId(), enrichmentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Enrichment cannot be rejected");
        }
        audit.record(user.id(), "ai.enrichment.reject", "ai_enrichment", enrichmentId.toString(),
                Map.of("documentId", documentId.toString()));
        return toMap(enrichments.find(doc.tenantId(), enrichmentId).orElseThrow());
    }

    /**
     * Worker entry: run provider, validate, store proposal. Does not mutate ClearDoc source.
     * No tools, URL fetch, permission changes, or unrelated document writes.
     */
    public void processJob(UUID tenantId, UUID enrichmentId) throws Exception {
        connectionConfig.runWithTenant(tenantId, () -> {
            processJobWithTenant(tenantId, enrichmentId);
            return null;
        });
    }

    private void processJobWithTenant(UUID tenantId, UUID enrichmentId) throws Exception {
        var record = enrichments.find(tenantId, enrichmentId)
                .orElseThrow(() -> new IllegalStateException("Enrichment not found"));
        WorkspaceSettings ws = settings.getOrDefaults(tenantId);
        if (!ws.aiEnabled()) {
            enrichments.markFailed(enrichmentId, "AI disabled");
            return;
        }
        AiProvider provider = providers.require(record.providerId());
        if (!provider.isLocal() && (ws.aiLocalOnly() || !ws.aiAllowExternal())) {
            enrichments.markFailed(enrichmentId, "External AI blocked by policy");
            return;
        }

        DocumentRecord doc = documentRepository.findByTenantAndId(tenantId, record.documentId())
                .orElseThrow(() -> new IllegalStateException("Document not found"));
        String key = documentRepository.findCleardocKey(doc.tenantId(), doc.id(), doc.currentRevisionId())
                .orElseThrow(() -> new IllegalStateException("ClearDoc missing"));
        ClearDoc cleardoc = objectMapper.readValue(storage.getBytes(key), ClearDoc.class);
        List<AiProvider.BlockExcerpt> excerpts = extractExcerpts(cleardoc);
        Set<String> knownIds = excerpts.stream().map(AiProvider.BlockExcerpt::blockNodeId).collect(Collectors.toSet());

        var system = enrichments.systemGuard().orElseThrow();
        var template = enrichments.latestTemplate(record.kind()).orElseThrow();

        AiProvider.EnrichmentRequest request = new AiProvider.EnrichmentRequest(
                record.kind(),
                system.bodyText(),
                template.bodyText(),
                template.id(),
                template.version(),
                excerpts,
                Map.of()
        );

        int suspicious = 0;
        for (AiProvider.BlockExcerpt e : excerpts) {
            if (PromptInjectionGuard.scanAndWrap(e.text()).suspicious()) {
                suspicious++;
            }
        }
        if (suspicious > 0) {
            log.info("Enrichment {} saw {} suspicious excerpt(s); treating as untrusted data", enrichmentId, suspicious);
        }

        AiProvider.EnrichmentResponse response = null;
        Exception last = null;
        int retries = Math.max(0, Math.min(ws.aiMaxRetries(), properties.getAi().getMaxRetries()));
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                response = provider.enrich(request);
                break;
            } catch (Exception ex) {
                last = ex;
                log.warn("AI provider attempt {} failed for {}: {}", attempt + 1, enrichmentId, ex.getMessage());
            }
        }
        if (response == null) {
            enrichments.markFailed(enrichmentId, last == null ? "provider failed" : last.getMessage());
            audit.recordSystem(tenantId, "ai.enrichment.failed", "ai_enrichment", enrichmentId.toString(),
                    Map.of("kind", record.kind(), "provider", provider.providerId()));
            throw last != null ? last : new IllegalStateException("provider failed");
        }

        var validation = EnrichmentValidator.validate(record.kind(), response.proposal(), knownIds);
        String proposalJson = objectMapper.writeValueAsString(response.proposal());
        String validationJson = objectMapper.writeValueAsString(validation.report());
        if (!validation.valid()) {
            enrichments.markFailed(enrichmentId, "Validation failed: " + String.join("; ", validation.errors()));
            audit.recordSystem(tenantId, "ai.enrichment.invalid", "ai_enrichment", enrichmentId.toString(),
                    Map.of("kind", record.kind(), "errors", validation.errors().size()));
            return;
        }
        enrichments.markReady(enrichmentId, response.modelId(), proposalJson, validationJson);
        enrichments.recordUsage(tenantId, record.documentId(), enrichmentId, response.providerId(),
                record.kind(), response.tokensIn(), response.tokensOut());
        audit.recordSystem(tenantId, "ai.enrichment.ready", "ai_enrichment", enrichmentId.toString(),
                Map.of("kind", record.kind(), "provider", response.providerId(), "model", response.modelId()));
    }

    @SuppressWarnings("unchecked")
    static List<AiProvider.BlockExcerpt> extractExcerpts(ClearDoc doc) {
        List<AiProvider.BlockExcerpt> out = new ArrayList<>();
        if (doc == null || doc.body() == null) {
            return out;
        }
        for (Map<String, Object> node : doc.body()) {
            collectBlock(node, out);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectBlock(Map<String, Object> node, List<AiProvider.BlockExcerpt> out) {
        if (node == null) {
            return;
        }
        String type = String.valueOf(node.getOrDefault("type", ""));
        String nodeId = node.get("nodeId") == null ? "" : String.valueOf(node.get("nodeId"));
        if (!nodeId.isBlank()) {
            String text = blockText(node);
            int start = 0;
            int end = Math.max(0, text.length());
            Object provenance = node.get("provenance");
            if (provenance instanceof Map<?, ?> p) {
                Object range = p.get("sourceRange");
                if (range instanceof Map<?, ?> r) {
                    if (r.get("startOffset") instanceof Number n) {
                        start = n.intValue();
                    }
                    if (r.get("endOffset") instanceof Number n) {
                        end = n.intValue();
                    }
                }
            }
            out.add(new AiProvider.BlockExcerpt(nodeId, type, text, start, end));
        }
        Object items = node.get("items");
        if (items instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    collectBlock((Map<String, Object>) m, out);
                }
            }
        }
    }

    private static String blockText(Map<String, Object> node) {
        Object text = node.get("text");
        if (text instanceof String s) {
            return s;
        }
        Object preview = node.get("valuePreview");
        if (preview instanceof String s) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        collectText(node.get("children"), sb);
        collectText(node.get("items"), sb);
        return sb.toString().trim();
    }

    private static void collectText(Object node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        if (node instanceof String s) {
            sb.append(s).append(' ');
            return;
        }
        if (node instanceof List<?> list) {
            for (Object o : list) {
                collectText(o, sb);
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            Object value = map.get("value");
            if (value instanceof String) {
                sb.append(value).append(' ');
            }
            Object text = map.get("text");
            if (text instanceof String) {
                sb.append(text).append(' ');
            }
            collectText(map.get("children"), sb);
            collectText(map.get("items"), sb);
        }
    }

    Map<String, Object> toMap(EnrichmentRepository.EnrichmentRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id().toString());
        m.put("documentId", r.documentId().toString());
        m.put("revisionId", r.revisionId() == null ? "" : r.revisionId().toString());
        m.put("kind", r.kind());
        m.put("status", r.status());
        m.put("providerId", r.providerId());
        m.put("modelId", r.modelId() == null ? "" : r.modelId());
        m.put("promptTemplateId", r.promptTemplateId());
        m.put("promptVersion", r.promptVersion());
        m.put("configHash", r.configHash());
        m.put("cacheKey", r.cacheKey());
        m.put("lastError", r.lastError() == null ? "" : r.lastError());
        m.put("generatedLabel", "AI-generated");
        try {
            if (r.proposalJson() != null) {
                m.put("proposal", objectMapper.readValue(r.proposalJson(), Map.class));
            } else {
                m.put("proposal", null);
            }
            if (r.validationJson() != null) {
                m.put("validation", objectMapper.readValue(r.validationJson(), Map.class));
            } else {
                m.put("validation", null);
            }
        } catch (Exception e) {
            m.put("proposal", null);
            m.put("validation", null);
        }
        return m;
    }

    private void requireAuthor() {
        String role = TenantContext.roleOrNull();
        if (!"author".equals(role) && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Author role required");
        }
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
