package com.hireai.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.agent.AgentCallback;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.agent.RecordedWorkOrder;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Only the Agent actually assigned to a task can submit its result (#72): on top of a valid
 * dispatch token, the callback must carry {@code X-HireAI-Signature} over the raw body, made with
 * the assigned Agent's CURRENT signing secret, timestamp within ±5 minutes. Every failure is 401
 * and leaves the task untouched (still waiting for its real result).
 *
 * <p>Runs under the DEFAULT (secured) profile, not the harness's permissive {@code test} profile,
 * so every status asserted is the full app's real one (see the 401-vs-403 post-mortem). Agents are
 * registered through the API so their secrets are the real issued ones.
 */
@ActiveProfiles(inheritProfiles = false)
class SignedCallbackIntegrationTest extends WholeAppIntegrationTest {

    private static final String SEED_PASSWORD = "DemoPass123!";
    private static final byte[] COMPLETED = AgentCallback.COMPLETED_BODY.getBytes(StandardCharsets.UTF_8);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private String builderJwt;
    private String clientJwt;

    @BeforeEach
    void logIn() throws Exception {
        builderJwt = login("builder@hireai.local");
        clientJwt = login("client@hireai.local");
    }

    @Test
    void aCallbackSignedWithTheAssignedAgentsSecretIsAccepted() throws Exception {
        Dispatched d = dispatchToRegisteredAgent();

        HttpResponse<String> response = callBack(d.order(), COMPLETED, sign(d.secret(), Instant.now(), COMPLETED));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(status(d.taskId())).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void aValidTokenAloneIsNotEnough() throws Exception {
        Dispatched d = dispatchToRegisteredAgent();

        assertRejected(d, callBack(d.order(), COMPLETED, null));
        // The raw body is authenticated before it is parsed: garbage without a signature is 401, not 400.
        assertRejected(d, callBack(d.order(), "{not json".getBytes(StandardCharsets.UTF_8), null));
        assertRejected(d, callBack(d.order(), COMPLETED, "t=" + Instant.now().getEpochSecond() + ",v1=" + "0".repeat(64)));
    }

    @Test
    void aWrongSecretOrAnotherAgentsSecretIsRejected() throws Exception {
        Dispatched d = dispatchToRegisteredAgent();
        String otherAgentsSecret = registerAgent("x-" + UUID.randomUUID()).path("signingSecret").asText();

        assertRejected(d, callBack(d.order(), COMPLETED, sign("agsec_" + "f".repeat(64), Instant.now(), COMPLETED)));
        assertRejected(d, callBack(d.order(), COMPLETED, sign(otherAgentsSecret, Instant.now(), COMPLETED)));
    }

    @Test
    void aStaleOrFutureTimestampIsRejected() throws Exception {
        Dispatched d = dispatchToRegisteredAgent();

        assertRejected(d, callBack(d.order(), COMPLETED,
                sign(d.secret(), Instant.now().minus(Duration.ofMinutes(6)), COMPLETED)));
        assertRejected(d, callBack(d.order(), COMPLETED,
                sign(d.secret(), Instant.now().plus(Duration.ofMinutes(6)), COMPLETED)));
    }

    @Test
    void aSignatureOverADifferentBodyIsRejected() throws Exception {
        Dispatched d = dispatchToRegisteredAgent();
        byte[] failed = "{\"agentStatus\":\"FAILED\",\"resultPayloadJson\":\"{}\"}".getBytes(StandardCharsets.UTF_8);

        // Signed the COMPLETED body, sent a FAILED one: a tampered body never reaches the task.
        assertRejected(d, callBack(d.order(), failed, sign(d.secret(), Instant.now(), COMPLETED)));
    }

    @Test
    void afterRotationTheOldSecretIsRejectedAndTheNewOneAccepted() throws Exception {
        Dispatched d = dispatchToRegisteredAgent();

        String fresh = send("POST", "/api/agents/" + d.agentId() + "/rotate-secret", builderJwt, null)
                .path("signingSecret").asText();
        assertThat(fresh).startsWith("agsec_").isNotEqualTo(d.secret());

        assertRejected(d, callBack(d.order(), COMPLETED, sign(d.secret(), Instant.now(), COMPLETED)));
        HttpResponse<String> accepted = callBack(d.order(), COMPLETED, sign(fresh, Instant.now(), COMPLETED));
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(status(d.taskId())).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void anAgentWithNoSecretCannotPostResultsUntilItRotates() throws Exception {
        Dispatched d = dispatchToRegisteredAgent();
        // An Agent registered before signing secrets existed (the migration left them NULL).
        jdbc.update("UPDATE agents SET signing_secret = NULL WHERE id = ?", d.agentId());

        assertRejected(d, callBack(d.order(), COMPLETED, null));
        assertRejected(d, callBack(d.order(), COMPLETED, sign(d.secret(), Instant.now(), COMPLETED)));

        String fresh = send("POST", "/api/agents/" + d.agentId() + "/rotate-secret", builderJwt, null)
                .path("signingSecret").asText();
        HttpResponse<String> accepted = callBack(d.order(), COMPLETED, sign(fresh, Instant.now(), COMPLETED));
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
    }

    @Test
    void aSecondSignedResultStillDoesNotOverwriteTheFirst() throws Exception {
        Dispatched d = dispatchToRegisteredAgent();
        byte[] failed = "{\"agentStatus\":\"FAILED\",\"resultPayloadJson\":\"{}\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(callBack(d.order(), COMPLETED, sign(d.secret(), Instant.now(), COMPLETED)).statusCode())
                .isEqualTo(200);
        assertThat(callBack(d.order(), failed, sign(d.secret(), Instant.now(), failed)).statusCode())
                .isEqualTo(200);

        assertThat(status(d.taskId())).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_results WHERE task_id = ?",
                Integer.class, d.taskId())).isEqualTo(1);
    }

    // ---- helpers ----

    private record Dispatched(UUID agentId, String secret, UUID taskId, RecordedWorkOrder order) {}

    /** Registers + activates an Agent over the API, routes a task to it, waits for EXECUTING. */
    private Dispatched dispatchToRegisteredAgent() throws Exception {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted()); // calls back manually
        String category = "x-" + UUID.randomUUID();
        JsonNode registered = registerAgent(category);
        UUID agentId = UUID.fromString(registered.path("id").asText());
        // Registration only accepts public HTTPS URLs; point the version at the local fake Agent.
        jdbc.update("UPDATE agent_versions SET webhook_url = ? WHERE agent_id = ?", agent.webhookUrl(), agentId);
        send("POST", "/api/agents/" + agentId + "/activate", builderJwt, null);

        UUID taskId = UUID.fromString(send("POST", "/api/tasks", clientJwt, """
                {"title":"Signed callback","description":"Summarise it","category":"%s","budget":10.00,
                 "outputSpec":{"format":"JSON","schema":"{}","acceptanceCriteria":"ok"}}
                """.formatted(category)).path("id").asText());
        RecordedWorkOrder order = agent.awaitWorkOrders(1, Duration.ofSeconds(20)).get(0);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("EXECUTING"));
        return new Dispatched(agentId, registered.path("signingSecret").asText(), taskId, order);
    }

    private JsonNode registerAgent(String category) throws Exception {
        JsonNode registered = send("POST", "/api/agents", builderJwt, """
                {"name":"Signed Agent","outputSpec":{"format":"JSON","schema":"{}","acceptanceCriteria":"ok"},
                 "capabilityCategories":["%s"],"webhookUrl":"https://agent.example.com/hook",
                 "maxExecutionSeconds":60,"price":5.00}
                """.formatted(category));
        assertThat(registered.path("signingSecret").asText()).startsWith("agsec_");
        return registered;
    }

    private void assertRejected(Dispatched d, HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(status(d.taskId())).isEqualTo("EXECUTING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_results WHERE task_id = ?",
                Integer.class, d.taskId())).isZero();
    }

    private static String sign(String secret, Instant at, byte[] body) {
        return AgentCallback.signature(secret, at, body);
    }

    /** POST exactly {@code body} with the WorkOrder's own (valid) token and the given signature. */
    private HttpResponse<String> callBack(RecordedWorkOrder order, byte[] body, String signature) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(order.callbackUrl()))
                .header("Authorization", "Bearer " + order.bearerToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (signature != null) {
            b.header(AgentCallback.SIGNATURE_HEADER, signature);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private String login(String email) throws Exception {
        return send("POST", "/api/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SEED_PASSWORD + "\"}").path("token").asText();
    }

    /** Sends and asserts 200; returns the envelope's {@code data}. */
    private JsonNode send(String method, String path, String jwt, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (jwt != null) {
            b.header("Authorization", "Bearer " + jwt);
        }
        HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path + " " + response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }
}
