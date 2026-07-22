package com.hireai.openapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5: springdoc publishes a scoped "programmatic" OpenAPI group, reachable without auth. Boots
 * the full app (real secured chain, no test profile). Auto-skips without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIf("dockerAvailable")
class OpenApiDocsIntegrationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void programmaticApiDocIsPublicAndScoped() {
        ResponseEntity<String> resp = rest.getForEntity(url("/v3/api-docs/programmatic"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat(body).contains("/api/tasks");
        assertThat(body).contains("/api/catalogue/agents");
        assertThat(body).doesNotContain("/api/admin");
    }

    @Test
    void swaggerUiIsPublic() {
        ResponseEntity<String> resp = rest.getForEntity(url("/swagger-ui/index.html"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void defaultApiDocDoesNotExposeAdminRoutes() {
        ResponseEntity<String> resp = rest.getForEntity(url("/v3/api-docs"), String.class);
        // The bare/ungrouped doc must never publish admin/internal routes to an anonymous caller.
        // (If springdoc doesn't serve a bare doc, the body is null/non-200 and the assertion holds.)
        if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
            assertThat(resp.getBody()).doesNotContain("/api/admin");
        }
    }
}
