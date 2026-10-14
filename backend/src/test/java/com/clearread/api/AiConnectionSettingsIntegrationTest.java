package com.clearread.api;

import com.clearread.TestJwtDecoderConfig;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class AiConnectionSettingsIntegrationTest {

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
        registry.add("clearread.ai.ollama.enabled", () -> "false");
        registry.add("clearread.ai.external.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    UUID tenantA = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM ai_connection_settings WHERE tenant_id = ?", tenantA);
        jdbc.update("INSERT INTO workspace_settings (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", tenantA);
        jdbc.update(
                """
                UPDATE workspace_settings SET ai_enabled = true, ai_local_only = true, ai_allow_external = false,
                  ai_provider_id = 'ollama', ai_daily_quota = 50 WHERE tenant_id = ?
                """,
                tenantA
        );
    }

    @Test
    void configureOllamaViaAdmin_makesProviderAvailable_andMasksApiKey() throws Exception {
        String statusBefore = mockMvc.perform(get("/v1/documents/" + pasteDoc() + "/enrichments/status")
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode before = objectMapper.readTree(statusBefore);
        assertThat(before.path("providerAvailable").asBoolean()).isFalse();
        assertThat(before.path("aiProviderId").asText()).isEqualTo("ollama");

        mockMvc.perform(put("/v1/tenants/" + tenantA + "/ai-connection")
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ollamaEnabled": true,
                                  "ollamaBaseUrl": "http://host.docker.internal:11434",
                                  "ollamaModel": "llama3.2",
                                  "externalEnabled": true,
                                  "externalBaseUrl": "https://api.example.com/v1/enrich",
                                  "externalModel": "gpt-test",
                                  "apiKey": "sk-super-secret-key-zyxw"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ollamaAvailable").value(true))
                .andExpect(jsonPath("$.externalAvailable").value(true))
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.apiKeyHint").value("••••zyxw"))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        String got = mockMvc.perform(get("/v1/tenants/" + tenantA + "/ai-connection")
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(got).doesNotContain("sk-super-secret-key");

        String statusAfter = mockMvc.perform(get("/v1/documents/" + pasteDoc() + "/enrichments/status")
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerAvailable").value(true))
                .andExpect(jsonPath("$.configurePath").value("/admin#ai-provider"))
                .andReturn().getResponse().getContentAsString();
        assertThat(statusAfter).contains("host.docker.internal");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.subject("admin-ai")
                .claim("preferred_username", "admin")
                .claim("email", "admin-ai@example.com")
                .claim("name", "Admin AI"));
    }

    private String pasteDoc() throws Exception {
        String content = "# AI connection\n\nHello ClearRead.";
        String body = mockMvc.perform(post("/v1/documents/paste")
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"AI Conn\",\"formatId\":\"markdown\",\"content\":%s}"
                                .formatted(objectMapper.writeValueAsString(content))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String docId = objectMapper.readTree(body).path("id").asText();
        for (int i = 0; i < 40; i++) {
            String st = jdbc.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, UUID.fromString(docId));
            if ("ready".equals(st) || "failed".equals(st)) {
                break;
            }
            Thread.sleep(200);
        }
        return docId;
    }
}
