package com.hireai.task.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * One matcher (#81), over the public HTTP API as the test-profile Client: the web shortlist and
 * routed submit share eligibility (ACTIVE, listed storefront, category, budget) and the score, so an
 * unlisted Agent is never routed (initially or on failover), and with exploration off (epsilon 0)
 * the shortlist's top Agent is the one a routed submit sends the WorkOrder to.
 */
@TestPropertySource(properties = "hireai.matching.epsilon=0.0")
class OneMatcherIntegrationTest extends WholeAppIntegrationTest {

    /** The test profile's fixed caller (DevCurrentUserProvider), seeded by V1 without a wallet. */
    private static final UUID DEV_CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(3);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void fundDevClient() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_CLIENT);
    }

    @Test
    void anUnlistedAgentIsNeverRoutedEvenWhenItWouldScoreBest() throws Exception {
        String category = uniqueCategory();
        FakeAgent unlisted = fakeAgent();
        FakeAgent listed = fakeAgent();
        seedAgent(category, unlisted, "95.00", "5.00", false);
        UUID listedVersion = seedAgent(category, listed, "50.00", "8.00", true);

        UUID taskId = submitRouted(category);

        listed.awaitWorkOrders(1, Duration.ofSeconds(20));
        assertThat(assignedVersion(taskId)).isEqualTo(listedVersion);
        await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.plusSeconds(5)).untilAsserted(() ->
                assertThat(unlisted.workOrders()).isEmpty());
    }

    @Test
    void aRoutedTaskWhoseOnlyAgentIsUnlistedWaitsForAnAgent() throws Exception {
        String category = uniqueCategory();
        FakeAgent unlisted = fakeAgent();
        seedAgent(category, unlisted, "95.00", "5.00", false);

        UUID taskId = submitRouted(category);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("AWAITING_AGENT"));
        assertThat(unlisted.workOrders()).isEmpty();
    }

    @Test
    void failoverNeverPicksAnUnlistedAgent() throws Exception {
        String category = uniqueCategory();
        FakeAgent busy = fakeAgent().replyWith(FakeAgentReply.status(429));
        FakeAgent unlisted = fakeAgent();
        seedAgent(category, busy, "95.00", "5.00", true);
        seedAgent(category, unlisted, "90.00", "5.00", false);

        UUID taskId = submitRouted(category);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isEqualTo("FAILED"));
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("NO_ALTERNATIVE_AGENT");
        assertThat(unlisted.workOrders()).isEmpty();
    }

    @Test
    void withExplorationOffTheShortlistTopAndTheRoutedPickAreTheSameAgent() throws Exception {
        String category = uniqueCategory();
        // Reputation and price pull in different directions, so the winner is the score's call,
        // not the candidate query's ORDER BY (reputation) nor the cheapest price.
        FakeAgent veteran = fakeAgent();
        FakeAgent bargain = fakeAgent();
        FakeAgent middling = fakeAgent();
        FakeAgent unlistedStar = fakeAgent();
        seedAgent(category, veteran, "90.00", "9.50", true);
        seedAgent(category, bargain, "60.00", "1.00", true);
        seedAgent(category, middling, "70.00", "6.00", true);
        seedAgent(category, unlistedStar, "99.00", "1.00", false);

        JsonNode preview = post("/api/tasks/match-preview", """
                {"title":"Digest","description":"Summarise","category":"%s","budget":10}""".formatted(category));
        String shortlistTop = preview.path("shortlist").get(0).path("agentId").asText();

        UUID taskId = submitRouted(category);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status(taskId)).isNotIn("SUBMITTED", "AWAITING_AGENT"));
        assertThat(jdbc.queryForObject("SELECT v.agent_id FROM tasks t JOIN agent_versions v "
                + "ON v.id = t.agent_version_id WHERE t.id = ?", UUID.class, taskId).toString())
                .isEqualTo(shortlistTop);
        assertThat(unlistedStar.workOrders()).isEmpty();
    }

    @Test
    void theBuildersAgentListSaysWhichAgentsAreUnlisted() throws Exception {
        // The test profile's caller is also the owner here, as a Builder looking at their dashboard.
        UUID listedAgent = agentOf(seedAgent(uniqueCategory(), fakeAgent(), "50.00", "5.00", true, DEV_CLIENT));
        UUID unlistedAgent = agentOf(seedAgent(uniqueCategory(), fakeAgent(), "50.00", "5.00", false, DEV_CLIENT));

        JsonNode mine = get("/api/agents");
        assertThat(listedFlag(mine, listedAgent)).isTrue();
        assertThat(listedFlag(mine, unlistedAgent)).isFalse();
        assertThat(get("/api/agents/" + unlistedAgent).path("listed").isBoolean()).isTrue();
        assertThat(get("/api/agents/" + unlistedAgent).path("listed").asBoolean()).isFalse();
        assertThat(get("/api/agents/" + listedAgent).path("listed").asBoolean()).isTrue();
    }

    private static boolean listedFlag(JsonNode agents, UUID agentId) {
        for (JsonNode a : agents) {
            if (a.path("id").asText().equals(agentId.toString())) {
                assertThat(a.path("listed").isBoolean()).as("listed flag on " + agentId).isTrue();
                return a.path("listed").asBoolean();
            }
        }
        throw new AssertionError("Agent " + agentId + " not in the list");
    }

    private UUID agentOf(UUID versionId) {
        return jdbc.queryForObject("SELECT agent_id FROM agent_versions WHERE id = ?", UUID.class, versionId);
    }

    private UUID submitRouted(String category) throws Exception {
        JsonNode task = post("/api/tasks", """
                {"title":"Quarterly digest","description":"Summarise the Q3 board pack","category":"%s",
                 "budget":10.00,"outputSpec":{"format":"JSON","acceptanceCriteria":"ok"}}""".formatted(category));
        return UUID.fromString(task.path("id").asText());
    }

    private String status(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private UUID assignedVersion(UUID taskId) {
        return jdbc.queryForObject("SELECT agent_version_id FROM tasks WHERE id = ?", UUID.class, taskId);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(platformUrl(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private JsonNode post(String path, String json) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    /** An ACTIVE Agent (its own Builder) whose current version points at {@code agent}. */
    private UUID seedAgent(String category, FakeAgent agent, String reputation, String price, boolean listed) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        return seedAgent(category, agent, reputation, price, listed, ownerId);
    }

    private UUID seedAgent(String category, FakeAgent agent, String reputation, String price, boolean listed,
                           UUID ownerId) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Matcher Agent', 'ACTIVE', ?, ?)", agentId, ownerId, versionId,
                new BigDecimal(reputation));
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category}, agent.webhookUrl(),
                new BigDecimal(price));
        jdbc.update("INSERT INTO agent_profiles (agent_id, tagline, is_listed) VALUES (?, 'T', ?)", agentId, listed);
        shareSigningSecret(agent);
        return versionId;
    }
}
