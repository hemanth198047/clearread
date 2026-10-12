package com.clearread.api;

import com.clearread.TestJwtDecoderConfig;
import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentRepository;
import com.clearread.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class DuplicateUploadIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("clearread")
            .withUsername("clearread")
            .withPassword("clearread");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.3"))
            .withServices(S3);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("clearread.storage.endpoint", () -> localstack.getEndpointOverride(S3).toString());
        registry.add("clearread.storage.access-key", localstack::getAccessKey);
        registry.add("clearread.storage.secret-key", localstack::getSecretKey);
        registry.add("clearread.storage.region", localstack::getRegion);
        registry.add("clearread.storage.bucket", () -> "clearread");
        registry.add("clearread.jobs.poll-interval-ms", () -> "200");
    }

    @Autowired MockMvc mockMvc;
    @Autowired DocumentRepository documentRepository;

    UUID tenantA = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void reuploadSameMarkdownOpensExisting_andForceNewCreatesCopy_andOriginalBytesMatch() throws Exception {
        byte[] content = "# Dup\n\nSame bytes twice.\n".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "dup.md", "text/markdown", content
        );

        String firstBody = mockMvc.perform(multipart("/v1/documents/upload")
                        .file(file)
                        .with(jwt().jwt(j -> j.subject("alice").claim("email", "alice@example.com").claim("name", "Alice")))
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateOfExisting").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String firstId = firstBody.replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        DocumentRecord ready = waitReady(tenantA, firstId);
        assertThat(ready.status()).isEqualTo("ready");
        assertThat(ready.originalSha256()).isEqualTo(ObjectStorageService.sha256(content));

        byte[] original = mockMvc.perform(get("/v1/documents/" + firstId + "/original")
                        .with(jwt().jwt(j -> j.subject("alice").claim("email", "alice@example.com").claim("name", "Alice")))
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(original).isEqualTo(content);

        MockMultipartFile again = new MockMultipartFile(
                "file", "dup.md", "text/markdown", content
        );
        mockMvc.perform(multipart("/v1/documents/upload")
                        .file(again)
                        .with(jwt().jwt(j -> j.subject("alice").claim("email", "alice@example.com").claim("name", "Alice")))
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.duplicateOfExisting").value(true))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already in your library")));

        MockMultipartFile forced = new MockMultipartFile(
                "file", "dup.md", "text/markdown", content
        );
        MockMultipartFile forceFlag = new MockMultipartFile(
                "forceNew", "", "text/plain", "true".getBytes(StandardCharsets.UTF_8)
        );
        String forcedBody = mockMvc.perform(multipart("/v1/documents/upload")
                        .file(forced)
                        .file(forceFlag)
                        .with(jwt().jwt(j -> j.subject("alice").claim("email", "alice@example.com").claim("name", "Alice")))
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateOfExisting").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String secondId = forcedBody.replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        assertThat(secondId).isNotEqualTo(firstId);
        DocumentRecord second = waitReady(tenantA, secondId);
        assertThat(second.status()).isEqualTo("ready");
        assertThat(second.originalSha256()).isEqualTo(ready.originalSha256());
    }

    private DocumentRecord waitReady(UUID tenantId, String docId) throws InterruptedException {
        DocumentRecord ready = null;
        for (int i = 0; i < 50; i++) {
            ready = documentRepository.findByTenantAndId(tenantId, UUID.fromString(docId)).orElseThrow();
            if ("ready".equals(ready.status()) || "failed".equals(ready.status())) {
                break;
            }
            Thread.sleep(300);
        }
        return ready;
    }
}
