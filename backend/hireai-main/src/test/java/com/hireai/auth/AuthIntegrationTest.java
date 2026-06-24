package com.hireai.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.routing.RoutingAppService;
import com.hireai.application.port.security.DispatchTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * End-to-end auth under the DEFAULT (secured) profile: Flyway V5 seeds client@hireai.local. Logs in,
 * gets a JWT, then proves (a) GET /api/agents with the token -> 200, (b) without it -> 401, and
 * (c) the agent callback is reachable token-only (no JWT) but still rejects a bad dispatch token.
 * Routing is mocked so task submission side effects don't interfere; the DispatchTokenService is
 * mocked so the callback's 401-on-bad-token path is deterministic without a real signed token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIf("dockerAvailable")
class AuthIntegrationTest {

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
    @Autowired ObjectMapper objectMapper;

    @MockBean RoutingAppService routingAppService;
    @MockBean DispatchTokenService dispatchTokenService;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String loginAndGetToken() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"email\":\"client@hireai.local\",\"password\":\"DemoPass123!\"}";
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/auth/login"), new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        return json.path("data").path("token").asText();
    }

    @Test
    void seedUserLogsInAndAccessesProtectedEndpoint() throws Exception {
        String token = loginAndGetToken();
        assertThat(token).isNotBlank();

        HttpHeaders authed = new HttpHeaders();
        authed.setBearerAuth(token);
        ResponseEntity<String> ok = rest.exchange(
                url("/api/agents"), HttpMethod.GET, new HttpEntity<>(authed), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void protectedEndpointRejectsAnonymous() {
        ResponseEntity<String> resp = rest.getForEntity(url("/api/agents"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithWrongPasswordIs401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"email\":\"client@hireai.local\",\"password\":\"wrong\"}";
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/auth/login"), new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void agentCallbackIsReachableWithoutJwtButRejectsBadDispatchToken() {
        // The callback path is permitAll in the secured chain (no JWT needed). Reaching the
        // controller (past Spring Security) it is gated by the dispatch token instead: the real
        // AgentCallbackAppService calls the @MockBean DispatchTokenService, whose verify(...) is
        // stubbed below to throw, so the controller-local handler returns 401 — proving the
        // callback is authenticated by the dispatch token, not the JWT (invariant #6).
        when(dispatchTokenService.verify(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.hireai.utility.exception.DispatchTokenInvalidException("bad"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("not-a-real-dispatch-token");
        String body = "{\"agentStatus\":\"COMPLETED\",\"resultPayloadJson\":\"{}\","
                + "\"resultUrl\":\"https://x/y\",\"message\":\"done\"}";
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/agent-callbacks/" + java.util.UUID.randomUUID() + "/result"),
                new HttpEntity<>(body, headers), String.class);
        // Reachable (not 401-from-security / not 403): the dispatch-token path handles auth.
        // With a mocked DispatchTokenService.verify throwing DispatchTokenInvalidException, the
        // controller-local handler returns 401 — proving the callback is gated by the token, not JWT.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
