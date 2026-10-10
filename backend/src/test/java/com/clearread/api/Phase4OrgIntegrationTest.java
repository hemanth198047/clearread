package com.clearread.api;

import com.clearread.TestJwtDecoderConfig;
import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentRepository;
import com.clearread.identity.UserRecord;
import com.clearread.identity.UserRepository;
import com.clearread.tenancy.MembershipRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestJwtDecoderConfig.class)
@Testcontainers
class Phase4OrgIntegrationTest {

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
    @Autowired UserRepository userRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired ObjectMapper objectMapper;

    UUID tenantA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID tenantB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void seed() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Integer.class, tenantB);
        if (count != null && count == 0) {
            jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, 'other', 'Other Workspace')", tenantB);
        }
        jdbc.update("INSERT INTO workspace_settings (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", tenantA);
        jdbc.update("INSERT INTO workspace_settings (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", tenantB);
    }

    @Test
    void exportShareSearch_andCrossTenantDenied() throws Exception {
        String content = "# Hello Phase4\n\nUniqueZebraToken for search.\n";
        String body = mockMvc.perform(post("/v1/documents/paste")
                        .with(jwt().jwt(j -> j.subject("alice4").claim("email", "alice4@example.com").claim("name", "Alice4")))
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Phase4 Searchable","formatId":"markdown","content":%s}
                                """.formatted(jsonString(content))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String docId = objectMapper.readTree(body).path("id").asText();

        DocumentRecord ready = null;
        for (int i = 0; i < 50; i++) {
            ready = documentRepository.findByTenantAndId(tenantA, UUID.fromString(docId)).orElseThrow();
            if ("ready".equals(ready.status()) || "failed".equals(ready.status())) {
                break;
            }
            Thread.sleep(250);
        }
        assertThat(ready).isNotNull();
        assertThat(ready.status()).isEqualTo("ready");

        mockMvc.perform(post("/v1/documents/" + docId + "/exports")
                        .with(jwt().jwt(j -> j.subject("alice4").claim("email", "alice4@example.com").claim("name", "Alice4")))
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetFormat\":\"html\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactKind").value("generated"))
                .andExpect(jsonPath("$.status").value("ready"));

        String expires = OffsetDateTime.now().plusDays(1).toString();
        String shareBody = mockMvc.perform(post("/v1/documents/" + docId + "/share-links")
                        .with(jwt().jwt(j -> j.subject("alice4").claim("email", "alice4@example.com").claim("name", "Alice4")))
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"" + expires + "\",\"requireAuth\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(shareBody).doesNotContain("token_hash");

        UserRecord bob = userRepository.upsert("bob4", "bob4@example.com", "Bob4");
        membershipRepository.ensureMembership(tenantB, bob.id(), "admin");

        mockMvc.perform(get("/v1/documents/" + docId)
                        .with(jwt().jwt(j -> j.subject("bob4").claim("email", "bob4@example.com").claim("name", "Bob4")))
                        .header("X-Tenant-Id", tenantB.toString()))
                .andExpect(status().isNotFound());

        String searchB = mockMvc.perform(get("/v1/search").param("q", "UniqueZebraToken")
                        .with(jwt().jwt(j -> j.subject("bob4").claim("email", "bob4@example.com").claim("name", "Bob4")))
                        .header("X-Tenant-Id", tenantB.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode resultsB = objectMapper.readTree(searchB).path("results");
        assertThat(resultsB.size()).isZero();

        String searchA = mockMvc.perform(get("/v1/search").param("q", "UniqueZebraToken")
                        .with(jwt().jwt(j -> j.subject("alice4").claim("email", "alice4@example.com").claim("name", "Alice4")))
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(searchA).path("totalReturned").asInt()).isGreaterThanOrEqualTo(1);
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
