package com.hireai.task.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.embedding.FakeEmbeddingProvider;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Relevance ranking (#85, ADR 0006), over the public HTTP API as the test-profile Client, with the fake
 * embedding endpoint answering fixed vectors. Agent vectors are written straight into
 * {@code agent_embeddings} at a chosen angle to the task's vector, so each Agent's raw cosine
 * similarity is known: the tests assert only on the shortlist order and on which Agent receives the
 * WorkOrder, never on scores. Default τ (0.25), exploration off.
 */
@TestPropertySource(properties = "hireai.matching.epsilon=0.0")
class RelevanceRankingIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String MODEL = "text-embedding-3-small";
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(3);

    private final FakeEmbeddingProvider provider = FakeEmbeddingProvider.shared();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Unique per test, so this test's task texts (and their cached vectors) are its own. */
    private String marker;

    @BeforeEach
    void setUp() {
        provider.reset();
        marker = UUID.randomUUID().toString();
        // Every task text of this test points along the first axis.
        provider.vectorFor(text -> FakeEmbeddingProvider.direction(0));
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_CLIENT);
    }

    @AfterEach
    void resetProvider() {
        provider.reset();
    }

    @Test
    void aSpecialistRanksAboveAGeneralistForAMatchingDescription() throws Exception {
        String category = uniqueCategory();
        FakeAgent specialist = fakeAgent();
        FakeAgent generalist = fakeAgent();
        // The generalist has the better record; the specialist's profile is much closer to the task.
        UUID specialistAgent = seedAgent(category, specialist, "50.00", "5.00", 0.90);
        UUID generalistAgent = seedAgent(category, generalist, "60.00", "5.00", 0.45);

        assertThat(shortlist(preview(category, "10"))).containsExactly(specialistAgent, generalistAgent);

        UUID taskId = submitRouted(category);
        specialist.awaitWorkOrders(1, Duration.ofSeconds(20));
        assertThat(assignedAgent(taskId)).isEqualTo(specialistAgent);
        assertThat(generalist.workOrders()).isEmpty();
    }

    @Test
    void anAgentBelowTheFloorIsExcludedFromTheShortlistAndRouting() throws Exception {
        String category = uniqueCategory();
        FakeAgent offTopic = fakeAgent();
        UUID offTopicAgent = seedAgent(category, offTopic, "99.00", "1.00", 0.10);
        UUID offTopicPricey = seedAgent(category, fakeAgent(), "90.00", "50.00", 0.05);
        UUID relevantPricey = seedAgent(category, fakeAgent(), "40.00", "40.00", 0.70);

        JsonNode preview = preview(category, "10");
        assertThat(shortlist(preview)).isEmpty();
        assertThat(preview.path("noCloseMatch").asBoolean()).isTrue();
        // Near-misses above the budget are still suggested, but only relevant ones.
        assertThat(ids(preview.path("nearMisses"))).containsExactly(relevantPricey)
                .doesNotContain(offTopicAgent, offTopicPricey);

        UUID taskId = submitRouted(category);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("AWAITING_AGENT"));
        await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.plusSeconds(5)).untilAsserted(() ->
                assertThat(offTopic.workOrders()).isEmpty());
    }

    @Test
    void anEmptyCategoryIsNotReportedAsNoCloseMatch() throws Exception {
        JsonNode preview = preview(uniqueCategory(), "10");
        assertThat(shortlist(preview)).isEmpty();
        assertThat(preview.path("noCloseMatch").asBoolean()).isFalse();
    }

    @Test
    void withTheProviderDownRankingFallsBackToTheOtherFourFactors() throws Exception {
        provider.down();
        String category = uniqueCategory();
        FakeAgent specialist = fakeAgent();
        FakeAgent veteran = fakeAgent();
        // With a task vector the veteran would be below τ; without one, its record wins.
        UUID specialistAgent = seedAgent(category, specialist, "50.00", "5.00", 0.90);
        UUID veteranAgent = seedAgent(category, veteran, "90.00", "5.00", 0.10);

        JsonNode preview = preview(category, "10");
        assertThat(shortlist(preview)).containsExactly(veteranAgent, specialistAgent);
        assertThat(preview.path("noCloseMatch").asBoolean()).isFalse();

        UUID taskId = submitRouted(category);
        veteran.awaitWorkOrders(1, Duration.ofSeconds(20));
        assertThat(assignedAgent(taskId)).isEqualTo(veteranAgent);
    }

    @Test
    void anAgentWithoutAVectorRanksNeutrally() throws Exception {
        String category = uniqueCategory();
        UUID close = seedAgent(category, fakeAgent(), "50.00", "5.00", 0.90);
        UUID noVector = seedAgent(category, fakeAgent(), "50.00", "5.00", null);
        UUID distant = seedAgent(category, fakeAgent(), "50.00", "5.00", 0.30);

        assertThat(shortlist(preview(category, "10"))).containsExactly(close, noVector, distant);
    }

    @Test
    void anAgentVectorFromAnotherModelIsNeverCompared() throws Exception {
        String category = uniqueCategory();
        UUID close = seedAgent(category, fakeAgent(), "50.00", "5.00", 0.90);
        // Below τ, but recorded with another model: it must count as "no vector", not as off-topic.
        UUID otherModel = seedAgent(category, fakeAgent(), "50.00", "5.00", null);
        storeVector(otherModel, "some-older-model", 0.05);
        UUID distant = seedAgent(category, fakeAgent(), "50.00", "5.00", 0.30);

        assertThat(shortlist(preview(category, "10"))).containsExactly(close, otherModel, distant);
    }

    @Test
    void bookingOrSubmittingAfterAPreviewMakesNoSecondEmbeddingCall() throws Exception {
        String category = uniqueCategory();
        FakeAgent agent = fakeAgent();
        UUID agentId = seedAgent(category, agent, "50.00", "5.00", 0.90);

        preview(category, "10");
        assertThat(taskTextRequests()).hasSize(1);

        post("/api/tasks/direct", """
                {"title":"Lease digest %s","description":"Summarise my lease","budget":5.00,"agentId":"%s"}"""
                .formatted(marker, agentId));
        UUID routed = submitRouted(category);
        agent.awaitWorkOrders(2, Duration.ofSeconds(20));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(routed)).isNotIn("SUBMITTED", "AWAITING_AGENT"));

        assertThat(taskTextRequests()).hasSize(1);
    }

    // ---- helpers ----

    private List<FakeEmbeddingProvider.Recorded> taskTextRequests() {
        return provider.requests().stream().filter(r -> r.input().contains(marker)).toList();
    }

    private JsonNode preview(String category, String budget) throws Exception {
        return post("/api/tasks/match-preview", """
                {"title":"Lease digest %s","description":"Summarise my lease","category":"%s","budget":%s}"""
                .formatted(marker, category, budget));
    }

    private UUID submitRouted(String category) throws Exception {
        JsonNode task = post("/api/tasks", """
                {"title":"Lease digest %s","description":"Summarise my lease","category":"%s",
                 "budget":10.00,"outputSpec":{"format":"JSON","acceptanceCriteria":"ok"}}"""
                .formatted(marker, category));
        return UUID.fromString(task.path("id").asText());
    }

    private static List<UUID> shortlist(JsonNode preview) {
        return ids(preview.path("shortlist"));
    }

    private static List<UUID> ids(JsonNode options) {
        List<UUID> out = new ArrayList<>();
        options.forEach(o -> out.add(UUID.fromString(o.path("agentId").asText())));
        return out;
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private UUID assignedAgent(UUID taskId) {
        return jdbc.queryForObject("SELECT v.agent_id FROM tasks t JOIN agent_versions v "
                + "ON v.id = t.agent_version_id WHERE t.id = ?", UUID.class, taskId);
    }

    private JsonNode post(String path, String json) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    /**
     * A listed ACTIVE Agent (its own Builder) pointing at {@code agent}, with a profile vector whose raw
     * cosine similarity to the task text is {@code similarity} (null = no vector). Returns the agent id.
     */
    private UUID seedAgent(String category, FakeAgent agent, String reputation, String price, Double similarity) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Relevance Agent', 'ACTIVE', ?, ?)", agentId, ownerId, versionId,
                new BigDecimal(reputation));
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, agent.webhookUrl(),
                new BigDecimal(price));
        jdbc.update("INSERT INTO agent_profiles (agent_id, tagline, is_listed) VALUES (?, 'T', true)", agentId);
        shareSigningSecret(agent);
        if (similarity != null) {
            storeVector(agentId, MODEL, similarity);
        }
        return agentId;
    }

    private void storeVector(UUID agentId, String model, double similarity) {
        float[] v = FakeEmbeddingProvider.atCosineToAxis(similarity);
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            literal.append(i == 0 ? "" : ",").append(v[i]);
        }
        jdbc.update("INSERT INTO agent_embeddings (agent_id, model, text_hash, embedding, updated_at) "
                        + "VALUES (?, ?, 'seeded', CAST(? AS vector), ?)",
                agentId, model, literal.append(']').toString(), Timestamp.from(Instant.now()));
    }
}
