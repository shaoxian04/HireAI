package com.hireai.openapi;

import com.hireai.support.harness.HarnessContainers;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5: springdoc publishes a scoped "programmatic" OpenAPI group, reachable without auth. Boots
 * the full app (real secured chain, no test profile). Auto-skips without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("dockerAvailable")
class OpenApiDocsIntegrationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A database of its own in the shared harness Postgres, which outlives every Spring context. A
     * class-scoped container was stopped while this (secured-profile, sweepers-on) context stayed
     * cached, so at JVM exit a sweep blocked on the pool's 30s connection timeout and the fork outlived
     * surefire's exit timeout.
     */
    private static String jdbcUrl;

    private static synchronized String jdbcUrl() {
        if (jdbcUrl == null) {
            jdbcUrl = HarnessContainers.newDatabase("openapi_it_" + UUID.randomUUID().toString().replace("-", ""));
        }
        return jdbcUrl;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl());
        registry.add("spring.datasource.username", () -> HarnessContainers.postgres().getUsername());
        registry.add("spring.datasource.password", () -> HarnessContainers.postgres().getPassword());
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
        // springdoc serves a DEFAULT ungrouped doc at bare /v3/api-docs (empirically 200) alongside the
        // scoped /v3/api-docs/programmatic. Assert OK unconditionally so a future change that stops
        // serving it fails loud instead of vacuously passing, then assert it excludes admin routes
        // (springdoc.paths-to-match scopes this default doc to the programmatic surface).
        ResponseEntity<String> resp = rest.getForEntity(url("/v3/api-docs"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).doesNotContain("/api/admin");
    }
}
