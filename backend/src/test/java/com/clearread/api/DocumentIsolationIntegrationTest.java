package com.clearread.api;

import com.clearread.TestJwtDecoderConfig;
import com.clearread.documents.DocumentRecord;
import com.clearread.documents.DocumentRepository;
import com.clearread.identity.UserRecord;
import com.clearread.identity.UserRepository;
import com.clearread.storage.ObjectStorageService;
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
@Testcontainers(disabledWithoutDocker = true)
class DocumentIsolationIntegrationTest {

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
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired DocumentRepository documentRepository;

    UUID tenantA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID tenantB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void seedTenantB() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Integer.class, tenantB);
        if (count != null && count == 0) {
            jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, 'other', 'Other Workspace')", tenantB);
        }
    }

    @Test
    void pasteReadyReadAndChecksum_andCrossTenantDenied() throws Exception {
        String content = "# Hello\n\nWorld from ClearRead.\n";

        String body = mockMvc.perform(post("/v1/documents/paste")
                        .with(jwt().jwt(j -> j.subject("alice").claim("email", "alice@example.com").claim("name", "Alice")))
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Hello Doc","formatId":"markdown","content":%s}
                                """.formatted(toJsonString(content))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("queued"))
                .andReturn().getResponse().getContentAsString();

        String docId = body.replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        DocumentRecord ready = null;
        for (int i = 0; i < 50; i++) {
            ready = documentRepository.findByTenantAndId(tenantA, UUID.fromString(docId)).orElseThrow();
            if ("ready".equals(ready.status()) || "failed".equals(ready.status())) {
                break;
            }
            Thread.sleep(300);
        }
        assertThat(ready).isNotNull();
        assertThat(ready.status()).isEqualTo("ready");
        assertThat(ready.originalSha256()).isEqualTo(ObjectStorageService.sha256(content.getBytes()));

        mockMvc.perform(get("/v1/documents/" + docId + "/cleardoc")
                        .with(jwt().jwt(j -> j.subject("alice").claim("email", "alice@example.com").claim("name", "Alice")))
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outline[0].text").value("Hello"));

        byte[] original = mockMvc.perform(get("/v1/documents/" + docId + "/original")
                        .with(jwt().jwt(j -> j.subject("alice").claim("email", "alice@example.com").claim("name", "Alice")))
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(original).isEqualTo(content.getBytes());

        UserRecord bob = userRepository.upsert("bob", "bob@example.com", "Bob");
        membershipRepository.ensureMembership(tenantB, bob.id(), "admin");

        mockMvc.perform(get("/v1/documents/" + docId)
                        .with(jwt().jwt(j -> j.subject("bob").claim("email", "bob@example.com").claim("name", "Bob")))
                        .header("X-Tenant-Id", tenantB.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    private static String toJsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
