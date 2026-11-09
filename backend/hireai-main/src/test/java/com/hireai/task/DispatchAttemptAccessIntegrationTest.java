package com.hireai.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/tasks/{id}/attempts} is owner-only and reachable by the owner's API key (#75).
 * Runs under the DEFAULT (secured) profile so denied statuses are the full app's real ones: 401
 * from the chain, 404 from the ownership check (see the 401-vs-403 post-mortem).
 */
@ActiveProfiles(inheritProfiles = false)
class DispatchAttemptAccessIntegrationTest extends WholeAppIntegrationTest {

    private static final String SEED_PASSWORD = "DemoPass123!";
    private static final UUID SEED_CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskExecutionPort taskExecutionPort;
    @Autowired WalletWriteAppService walletWriteAppService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID taskId;
    private UUID versionId;

    @BeforeEach
    void seedTaskWithOneAttempt() {
        walletWriteAppService.topUp(SEED_CLIENT, Money.of("30.00"), "it-seed-" + UUID.randomUUID());
        taskId = taskWriteAppService.submit(new TaskSubmitInfo(SEED_CLIENT, "Summarise", "Summarise the report",
                Money.of("30.00"), new OutputSpec(OutputFormat.JSON, null, "valid JSON"), "x-" + UUID.randomUUID()));
        versionId = UUID.randomUUID();
        taskExecutionPort.recordDispatchAttempt(taskId, versionId, DispatchOutcome.ACCEPTED);
    }

    @Test
    void theOwnerReadsTheHistoryWithAJwt() throws Exception {
        HttpResponse<String> r = get(attemptsPath(), "Bearer " + login("client@hireai.local"));

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode data = mapper.readTree(r.body()).path("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(data.get(0).path("agentVersionId").asText()).isEqualTo(versionId.toString());
    }

    @Test
    void theOwnersApiKeyReadsTheHistory() throws Exception {
        String rawKey = createKey(login("client@hireai.local"));

        HttpResponse<String> r = get(attemptsPath(), "ApiKey " + rawKey);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(mapper.readTree(r.body()).path("data")).hasSize(1);
    }

    @Test
    void anotherClientGetsNotFoundAndNoRows() throws Exception {
        HttpResponse<String> r = get(attemptsPath(), "Bearer " + registerAnotherClient());

        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).doesNotContain(versionId.toString());
    }

    @Test
    void anAnonymousCallerIsUnauthorized() throws Exception {
        HttpResponse<String> r = get(attemptsPath(), null);

        assertThat(r.statusCode()).isEqualTo(401);
    }

    // ---- helpers ----

    private String attemptsPath() {
        return "/api/tasks/" + taskId + "/attempts";
    }

    private String login(String email) throws Exception {
        HttpResponse<String> r = send("POST", "/api/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SEED_PASSWORD + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("data").path("token").asText();
    }

    private String registerAnotherClient() throws Exception {
        String email = "client-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> reg = send("POST", "/api/auth/register", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SEED_PASSWORD + "\",\"displayName\":\"Other\"}");
        assertThat(reg.statusCode()).as(reg.body()).isEqualTo(200);
        return mapper.readTree(reg.body()).path("data").path("token").asText();
    }

    private String createKey(String jwt) throws Exception {
        HttpResponse<String> r = send("POST", "/api/keys", "Bearer " + jwt, "{\"name\":\"attempts-it\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("data").path("rawKey").asText();
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        return send("GET", path, authorization, null);
    }

    private HttpResponse<String> send(String method, String path, String authorization, String json)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
