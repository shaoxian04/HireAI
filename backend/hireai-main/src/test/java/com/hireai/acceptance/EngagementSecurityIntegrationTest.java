package com.hireai.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.support.harness.HarnessContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engagement routes under the DEFAULT (secured) profile, against the full app (the post-mortem: assert
 * denied status on the full chain, where forbidden renders as 401). The identity is the JWT's (Hard
 * Invariant #5): a stranger gets 404; opening an Engagement (consent) is human-only, while a machine
 * buyer's API key may upload and list; the signed source link needs no login (the token is the credential).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIf("dockerAvailable")
class EngagementSecurityIntegrationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(HarnessContainers.POSTGRES_IMAGE);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @MockBean RoutingAppService routingAppService;

    @Test
    void engagementsAreTheBuyersOwnAndDocumentsMayBeUploadedWithAKeyButConsentNeedsAPerson() throws Exception {
        String client = login("client@hireai.local");
        String builder = login("builder@hireai.local");
        UUID agentId = seedAgent();

        assertThat(rest.exchange(url("/api/engagements"), HttpMethod.GET, new HttpEntity<>(json(null)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> opened = rest.postForEntity(url("/api/engagements"),
                new HttpEntity<>("{\"agentId\":\"" + agentId + "\",\"confidentialConsent\":true}", json(client)),
                String.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        String engagementId = objectMapper.readTree(opened.getBody()).path("data").path("id").asText();

        // A Builder that does not own the Agent is a stranger: not found, and cannot accept.
        assertThat(status(HttpMethod.GET, "/api/engagements/" + engagementId, builder)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.POST, "/api/engagements/" + engagementId + "/accept", builder))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.GET, "/api/engagements/" + engagementId + "/sources", builder))
                .isEqualTo(HttpStatus.NOT_FOUND);

        // A machine buyer's key may upload and list, but not open or end an Engagement.
        String key = createKey(client);
        HttpHeaders keyHeaders = new HttpHeaders();
        keyHeaders.set("Authorization", "ApiKey " + key);
        keyHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("%PDF-1.7\nkey upload".getBytes(StandardCharsets.US_ASCII)) {
            @Override
            public String getFilename() {
                return "via-key.pdf";
            }
        });
        assertThat(rest.postForEntity(url("/api/engagements/" + engagementId + "/sources"),
                new HttpEntity<>(form, keyHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders keyJson = json(null);
        keyJson.set("Authorization", "ApiKey " + key);
        assertThat(rest.exchange(url("/api/engagements/" + engagementId + "/sources"), HttpMethod.GET,
                new HttpEntity<>(keyJson), String.class).getBody()).contains("via-key.pdf");
        assertThat(rest.postForEntity(url("/api/engagements"),
                new HttpEntity<>("{\"agentId\":\"" + agentId + "\",\"confidentialConsent\":true}", keyJson),
                String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.postForEntity(url("/api/engagements/" + engagementId + "/end"),
                new HttpEntity<>(keyJson), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aSourceLinkNeedsNoLoginItsTokenIsTheCredential() {
        ResponseEntity<String> forged = rest.getForEntity(url("/api/source-links/AAAA.BBBB"), String.class);
        assertThat(forged.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The controller answered (not the security chain, whose 401 has no body).
        assertThat(forged.getBody()).contains("not valid");
    }

    private UUID seedAgent() {
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", owner, owner + "@test.local");
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Invoice Agent', 'ACTIVE', ?, 80.00)", agentId, owner, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"TEXT\"}", new String[]{"summarisation"},
                "https://agent.example/hook", new BigDecimal("2.00"));
        return agentId;
    }

    private HttpStatus status(HttpMethod method, String path, String jwt) {
        return HttpStatus.valueOf(rest.exchange(url(path), method, new HttpEntity<>(json(jwt)), String.class)
                .getStatusCode().value());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String login(String email) throws Exception {
        ResponseEntity<String> r = rest.postForEntity(url("/api/auth/login"),
                new HttpEntity<>("{\"email\":\"" + email + "\",\"password\":\"DemoPass123!\"}", json(null)),
                String.class);
        return objectMapper.readTree(r.getBody()).path("data").path("token").asText();
    }

    private String createKey(String jwt) throws Exception {
        ResponseEntity<String> r = rest.postForEntity(url("/api/keys"),
                new HttpEntity<>("{\"name\":\"ap-pipeline\"}", json(jwt)), String.class);
        return objectMapper.readTree(r.getBody()).path("data").path("rawKey").asText();
    }

    private static HttpHeaders json(String jwt) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            h.setBearerAuth(jwt);
        }
        return h;
    }
}
