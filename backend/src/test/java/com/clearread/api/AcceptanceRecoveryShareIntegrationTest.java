package com.clearread.api;

import com.clearread.TestJwtDecoderConfig;
import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentRepository;
import com.clearread.jobs.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
@Testcontainers
class AcceptanceRecoveryShareIntegrationTest {

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
        registry.add("clearread.jobs.poll-interval-ms", () -> "150");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentRepository documentRepository;
    @Autowired JobRepository jobs;
    @Autowired ObjectMapper objectMapper;

    UUID tenantA = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO workspace_settings (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", tenantA);
    }

    @Test
    void shareExpireRevokeAndPdfExport_andFailReprocess() throws Exception {
        String content = "# Acceptance\n\nShare and PDF path.\n";
        String body = mockMvc.perform(post("/v1/documents/paste")
                        .with(jwt().jwt(j -> j.subject("acc1").claim("email", "acc1@example.com").claim("name", "Acc1")))
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Acceptance Doc","formatId":"markdown","content":%s}
                                """.formatted(jsonString(content))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String docId = objectMapper.readTree(body).path("id").asText();

        DocumentRecord ready = waitReady(docId);
        assertThat(ready.status()).isEqualTo("ready");

        // PDF constrained export
        mockMvc.perform(post("/v1/documents/" + docId + "/exports")
                        .with(jwt().jwt(j -> j.subject("acc1").claim("email", "acc1@example.com").claim("name", "Acc1")))
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetFormat\":\"pdf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactKind").value("generated"))
                .andExpect(jsonPath("$.targetFormat").value("pdf"))
                .andExpect(jsonPath("$.status").value("ready"));

        String expires = OffsetDateTime.now().plusHours(2).toString();
        String shareBody = mockMvc.perform(post("/v1/documents/" + docId + "/share-links")
                        .with(jwt().jwt(j -> j.subject("acc1").claim("email", "acc1@example.com").claim("name", "Acc1")))
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"" + expires + "\",\"requireAuth\":false,\"allowDownload\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andReturn().getResponse().getContentAsString();
        JsonNode share = objectMapper.readTree(shareBody);
        String token = share.path("token").asText();
        String linkId = share.path("id").asText();

        mockMvc.perform(get("/v1/shared/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(docId))
                .andExpect(jsonPath("$.allowDownload").value(false));

        mockMvc.perform(get("/v1/shared/" + token + "/original"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/v1/shared/" + token + "/cleardoc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").isArray());

        mockMvc.perform(delete("/v1/share-links/" + linkId)
                        .with(jwt().jwt(j -> j.subject("acc1").claim("email", "acc1@example.com").claim("name", "Acc1")))
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/shared/" + token))
                .andExpect(status().isGone());

        // Expired share
        String past = OffsetDateTime.now().minusMinutes(5).toString();
        String expiredBody = mockMvc.perform(post("/v1/documents/" + docId + "/share-links")
                        .with(jwt().jwt(j -> j.subject("acc1").claim("email", "acc1@example.com").claim("name", "Acc1")))
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"" + past + "\",\"requireAuth\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String expiredToken = objectMapper.readTree(expiredBody).path("token").asText();
        mockMvc.perform(get("/v1/shared/" + expiredToken))
                .andExpect(status().isGone());

        // Fail → diagnose → reprocess → ready
        jdbc.update("UPDATE documents SET status = 'failed' WHERE id = ?::uuid", docId);
        mockMvc.perform(get("/v1/documents/" + docId + "/diagnostics")
                        .with(jwt().jwt(j -> j.subject("acc1").claim("email", "acc1@example.com").claim("name", "Acc1")))
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.jobs").isArray());

        mockMvc.perform(post("/v1/documents/" + docId + "/reprocess")
                        .with(jwt().jwt(j -> j.subject("acc1").claim("email", "acc1@example.com").claim("name", "Acc1")))
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        DocumentRecord again = waitReady(docId);
        assertThat(again.status()).isEqualTo("ready");
    }

    private DocumentRecord waitReady(String docId) throws InterruptedException {
        DocumentRecord ready = null;
        for (int i = 0; i < 50; i++) {
            ready = documentRepository.findByTenantAndId(tenantA, UUID.fromString(docId)).orElseThrow();
            if ("ready".equals(ready.status()) || "failed".equals(ready.status())) {
                break;
            }
            Thread.sleep(250);
        }
        return ready;
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
