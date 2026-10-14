package com.clearread.documents;

import com.clearread.audit.AuditService;
import com.clearread.capabilities.CapabilityService;
import com.clearread.config.ClearReadProperties;
import com.clearread.identity.UserRecord;
import com.clearread.jobs.JobRepository;
import com.clearread.library.LibraryService;
import com.clearread.net.SafeHttpFetcher;
import com.clearread.storage.ObjectStorageService;
import com.clearread.tenancy.WorkspaceSettingsRepository;
import com.clearread.transform.TransformService;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.Metadata;
import com.clearread.transform.cleardoc.ClearDocModels.Source;
import com.clearread.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers ClearDoc read failures and missing-original purge (v1.2.8 — no replace-original UX).
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceClearDocReadTest {

    @Mock DocumentRepository documents;
    @Mock ObjectStorageService storage;
    @Mock TransformService transformService;
    @Mock CapabilityService capabilities;
    @Mock JobRepository jobs;
    @Mock ClearReadProperties properties;
    @Mock SafeHttpFetcher fetcher;
    @Mock WorkspaceSettingsRepository workspaceSettings;
    @Mock LibraryService libraryService;
    @Mock AuditService audit;

    ClearDocArtifactService clearDocArtifacts;
    DocumentService service;

    UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID documentId = UUID.fromString("50ecfb43-2fb2-44c2-871f-f930bcf96230");
    UUID revisionId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    UserRecord user = new UserRecord(userId, "sub", "u@example.com", "User");

    @BeforeEach
    void setUp() {
        clearDocArtifacts = new ClearDocArtifactService(storage, JsonMapper.builder().build());
        service = new DocumentService(
                documents, storage, clearDocArtifacts, transformService, capabilities, jobs,
                properties, JsonMapper.builder().build(), fetcher, workspaceSettings, libraryService, audit
        );
        TenantContext.set(tenantId, "author");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void getCleardoc_returns404_whenArtifactMissingFromStorage() {
        stubReadyDoc();
        when(storage.exists(any())).thenReturn(true);
        when(storage.getBytes(any())).thenThrow(
                software.amazon.awssdk.services.s3.model.NoSuchKeyException.builder()
                        .message("The specified key does not exist.")
                        .build()
        );

        assertThatThrownBy(() -> service.getCleardoc(user, documentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).contains("Reprocess");
                    assertThat(rse.getReason()).isNotEqualTo("Failed to read ClearDoc");
                });
    }

    @Test
    void getCleardoc_returnsDoc_whenArtifactPresent() throws Exception {
        stubReadyDoc();
        when(storage.exists(any())).thenReturn(true);
        ClearDoc doc = new ClearDoc(
                "1.0", documentId.toString(), revisionId.toString(), tenantId.toString(),
                new Source("markdown", "a", "1", "sha", "f.md", 3),
                new Metadata("T", null, null), List.of(), List.of(), List.of()
        );
        when(storage.getBytes(any())).thenReturn(JsonMapper.builder().build().writeValueAsBytes(doc));

        ClearDoc read = service.getCleardoc(user, documentId);
        assertThat(read.metadata().title()).isEqualTo("T");
    }

    @Test
    void getMeta_purgesAndReturns410_whenOriginalMissing() {
        DocumentRecord doc = new DocumentRecord(
                documentId, tenantId, "T", "markdown", "ready",
                "tenants/t/documents/d/original/sha", "sha", 10L, "f.md", userId, revisionId
        );
        when(documents.findByTenantAndId(any(), eq(documentId))).thenReturn(Optional.of(doc));
        when(documents.canRead(any(), eq(documentId), eq(userId), any())).thenReturn(true);
        when(storage.exists(doc.originalObjectKey())).thenReturn(false);

        assertThatThrownBy(() -> service.getMeta(user, documentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.GONE);
                    assertThat(rse.getReason()).containsIgnoringCase("Document removed");
                    assertThat(rse.getReason()).doesNotContain("replace-original");
                    assertThat(rse.getReason()).doesNotContainIgnoringCase("re-upload");
                });
        verify(documents).softDelete(tenantId, documentId);
        verify(audit).record(eq(userId), eq("document.purge_missing_original"), eq("document"),
                eq(documentId.toString()), any());
    }

    @Test
    void reprocess_purgesWhenOriginalObjectMissing() {
        DocumentRecord doc = new DocumentRecord(
                documentId, tenantId, "T", "markdown", "ready",
                "tenants/t/documents/d/original/sha", "sha", 10L, "f.md", userId, revisionId
        );
        when(documents.findByTenantAndId(any(), eq(documentId))).thenReturn(Optional.of(doc));
        when(documents.canRead(any(), eq(documentId), eq(userId), any())).thenReturn(true);
        when(storage.exists(doc.originalObjectKey())).thenReturn(false);

        assertThatThrownBy(() -> service.reprocess(user, documentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.GONE);
                    assertThat(rse.getReason()).containsIgnoringCase("Document removed");
                });
        verify(documents).softDelete(tenantId, documentId);
        verify(jobs, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void reprocess_enqueuesWhenOriginalExists() throws Exception {
        DocumentRecord doc = new DocumentRecord(
                documentId, tenantId, "T", "markdown", "ready",
                "tenants/t/documents/d/original/sha", "sha", 10L, "f.md", userId, revisionId
        );
        when(documents.findByTenantAndId(any(), eq(documentId))).thenReturn(Optional.of(doc));
        when(documents.canRead(any(), eq(documentId), eq(userId), any())).thenReturn(true);
        when(storage.exists(doc.originalObjectKey())).thenReturn(true);

        var result = service.reprocess(user, documentId);
        assertThat(result.get("jobId")).isNotNull();
        verify(documents).updateStatus(tenantId, documentId, "queued");
        verify(jobs).enqueue(any(), eq(tenantId), eq("transform.document"), any(), any());
    }

    @Test
    void softDedup_purgesOrphanAndCreatesFreshWhenOriginalMissing() throws Exception {
        String content = "# Same\n";
        byte[] bytes = content.getBytes();
        String sha = ObjectStorageService.sha256(bytes);
        String oldKey = "tenants/" + tenantId + "/documents/" + documentId + "/original/" + sha;
        DocumentRecord orphaned = new DocumentRecord(
                documentId, tenantId, "T", "markdown", "ready",
                oldKey, sha, (long) bytes.length, "f.md", userId, revisionId
        );
        when(properties.getLimits()).thenReturn(limits(1_000_000));
        when(workspaceSettings.getOrDefaults(tenantId)).thenReturn(ws(1_000_000));
        when(transformService.detectFormat(any(), any())).thenReturn("markdown");
        when(capabilities.supportsIngest("markdown")).thenReturn(true);
        when(documents.findByTenantAndOriginalSha(tenantId, sha)).thenReturn(Optional.of(orphaned));
        when(documents.canRead(tenantId, documentId, userId, "author")).thenReturn(true);
        when(storage.exists(oldKey)).thenReturn(false);

        when(storage.originalKey(eq(tenantId), any(UUID.class), eq(sha))).thenAnswer(inv -> {
            UUID id = inv.getArgument(1);
            return "tenants/" + tenantId + "/documents/" + id + "/original/" + sha;
        });
        when(storage.putBytes(any(), any(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return new ObjectStorageService.StoredObject(key, sha, bytes.length);
        });

        DocumentService.IngestResult result = service.ingestUpload(
                user, "T", "f.md", "text/markdown", bytes, false
        );

        assertThat(result.duplicateOfExisting()).isFalse();
        assertThat(result.document().id()).isNotEqualTo(documentId);
        verify(documents).softDelete(tenantId, documentId);
        verify(documents).insert(any());
        verify(jobs).enqueue(any(), eq(tenantId), eq("transform.document"), any(), any());
    }

    @Test
    void softDedup_returnsExistingWhenOriginalPresent() throws Exception {
        String content = "# Same\n";
        byte[] bytes = content.getBytes();
        String sha = ObjectStorageService.sha256(bytes);
        String key = "tenants/" + tenantId + "/documents/" + documentId + "/original/" + sha;
        DocumentRecord existing = new DocumentRecord(
                documentId, tenantId, "T", "markdown", "ready",
                key, sha, (long) bytes.length, "f.md", userId, revisionId
        );
        when(properties.getLimits()).thenReturn(limits(1_000_000));
        when(workspaceSettings.getOrDefaults(tenantId)).thenReturn(ws(1_000_000));
        when(transformService.detectFormat(any(), any())).thenReturn("markdown");
        when(capabilities.supportsIngest("markdown")).thenReturn(true);
        when(documents.findByTenantAndOriginalSha(tenantId, sha)).thenReturn(Optional.of(existing));
        when(documents.canRead(tenantId, documentId, userId, "author")).thenReturn(true);
        when(storage.exists(key)).thenReturn(true);

        DocumentService.IngestResult result = service.ingestUpload(
                user, "T", "f.md", "text/markdown", bytes, false
        );

        assertThat(result.duplicateOfExisting()).isTrue();
        assertThat(result.document().id()).isEqualTo(documentId);
        verify(documents, never()).softDelete(any(), any());
        verify(documents, never()).insert(any());
    }

    @Test
    void shouldPurge_skipsInFlightUrlFetchWithoutOriginalKey() {
        DocumentRecord fetching = new DocumentRecord(
                documentId, tenantId, "Remote", "unknown", "queued",
                null, null, null, null, userId, null
        );
        assertThat(service.shouldPurgeForMissingOriginal(fetching)).isFalse();
    }

    private static ClearReadProperties.Limits limits(long maxUpload) {
        ClearReadProperties.Limits l = new ClearReadProperties.Limits();
        l.setMaxUploadBytes(maxUpload);
        return l;
    }

    private WorkspaceSettingsRepository.WorkspaceSettings ws(long maxUpload) {
        return new WorkspaceSettingsRepository.WorkspaceSettings(
                tenantId, true, true, null, maxUpload, 100, true,
                false, true, false, "mock-local", 50, 2
        );
    }

    private void stubReadyDoc() {
        DocumentRecord doc = new DocumentRecord(
                documentId, tenantId, "T", "markdown", "ready",
                "tenants/t/documents/d/original/sha", "sha", 10L, "f.md", userId, revisionId
        );
        when(documents.findByTenantAndId(any(), eq(documentId))).thenReturn(Optional.of(doc));
        when(documents.canRead(any(), eq(documentId), eq(userId), any())).thenReturn(true);
        when(documents.findCleardocKey(tenantId, documentId, revisionId))
                .thenReturn(Optional.of("tenants/t/documents/d/revisions/r/cleardoc.json"));
    }
}
