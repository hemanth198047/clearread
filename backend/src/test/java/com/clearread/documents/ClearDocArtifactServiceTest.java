package com.clearread.documents;

import com.clearread.storage.ObjectStorageService;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.Metadata;
import com.clearread.transform.cleardoc.ClearDocModels.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClearDocArtifactServiceTest {

    @Mock ObjectStorageService storage;

    ClearDocArtifactService service;
    tools.jackson.databind.ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        service = new ClearDocArtifactService(storage, objectMapper);
    }

    @Test
    void read_returnsClearDoc_whenObjectPresent() throws Exception {
        ClearDoc doc = sampleDoc();
        byte[] json = objectMapper.writeValueAsBytes(doc);
        when(storage.getBytes("tenants/t/documents/d/revisions/r/cleardoc.json")).thenReturn(json);

        ClearDoc read = service.read("tenants/t/documents/d/revisions/r/cleardoc.json", UUID.randomUUID());

        assertThat(read.schemaVersion()).isEqualTo(doc.schemaVersion());
        assertThat(read.metadata().title()).isEqualTo("Sample");
        assertThat(read.body()).hasSize(1);
    }

    @Test
    void read_returns404_whenS3KeyMissing() {
        when(storage.getBytes("missing-key")).thenThrow(
                NoSuchKeyException.builder()
                        .message("The specified key does not exist.")
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").build())
                        .build()
        );

        assertThatThrownBy(() -> service.read("missing-key", UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).contains("Reprocess");
                    assertThat(rse.getReason()).doesNotContain("Failed to read ClearDoc");
                });
    }

    @Test
    void read_returns404_whenS3Returns404Status() {
        when(storage.getBytes("gone")).thenThrow(
                S3Exception.builder()
                        .statusCode(404)
                        .message("Not Found")
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NotFound").build())
                        .build()
        );

        assertThatThrownBy(() -> service.read("gone", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void read_returns409_whenJsonCorrupt() {
        when(storage.getBytes("bad-json")).thenReturn("not-json{{{".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.read("bad-json", UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).containsIgnoringCase("corrupt");
                    assertThat(rse.getReason()).contains("Reprocess");
                });
    }

    @Test
    void read_returns409_whenObjectEmpty() {
        when(storage.getBytes("empty")).thenReturn(new byte[0]);

        assertThatThrownBy(() -> service.read("empty", UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void looksLikeMissingObject_detectsNestedNoSuchKey() {
        RuntimeException wrapped = new RuntimeException(
                "get failed",
                NoSuchKeyException.builder().message("NoSuchKey").build()
        );
        assertThat(ClearDocArtifactService.looksLikeMissingObject(wrapped)).isTrue();
        assertThat(ClearDocArtifactService.looksLikeMissingObject(new RuntimeException("timeout"))).isFalse();
    }

    private static ClearDoc sampleDoc() {
        return new ClearDoc(
                "1.0",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                new Source("markdown", "markdown-commonmark-gfm", "1.0.1", "abc", "a.md", 12),
                new Metadata("Sample", null, null),
                List.of(),
                List.of(),
                List.of(java.util.Map.of("type", "paragraph", "nodeId", "n1"))
        );
    }
}
