package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Template version transition (#92, spec D36), over the public HTTP API. An Offer covers only the
 * versions it names; new Contracts use the current version; during the transition (30 days by default)
 * a Contract falls back to the previous version when no Offer covers the current one, and the Client
 * is told; afterwards the old version takes no new Contracts or Offer terms.
 *
 * <p>Each test publishes its own Template line under a fresh key: {@code contract_templates} rows can't be
 * deleted, and a new version of a shared generic Template would change every other test's Contracts.
 */
class TemplateTransitionIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Duration TRANSITION = Duration.ofDays(30);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void fundDevUser() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_USER);
    }

    @Test
    void duringTheTransitionAPreviewFallsBackToThePreviousVersionWhenNoOfferCoversTheCurrentOne() throws Exception {
        String key = uniqueKey();
        Instant v2Published = publishLine(key, Duration.ofDays(1));
        String category = uniqueCategory();
        UUID onV1 = seedAgent(category, offerFor(key, 1));

        JsonNode preview = post("/api/tasks/match-preview", previewBody(category, contract(key, 2, 1000)));

        assertThat(agentIds(preview.path("shortlist"))).containsExactly(onV1.toString());
        JsonNode fallback = preview.path("fallback");
        assertThat(fallback.path("template").asText()).isEqualTo(key);
        assertThat(fallback.path("currentVersion").asInt()).isEqualTo(2);
        assertThat(fallback.path("usedVersion").asInt()).isEqualTo(1);
        assertThat(Instant.parse(fallback.path("transitionEndsAt").asText()))
                .isCloseTo(v2Published.plus(TRANSITION), within());
        assertThat(fallback.path("summary").asText()).contains("No agent accepts " + key + "@2 yet", key + "@1");

        // Once an Offer covers the current version, new Contracts use it, and the v1-only Agent no longer
        // matches: an Offer covers only the versions it names.
        UUID onV2 = seedAgent(category, offerFor(key, 2));
        JsonNode current = post("/api/tasks/match-preview", previewBody(category, contract(key, 1, 1000)));
        assertThat(agentIds(current.path("shortlist"))).containsExactly(onV2.toString());
        assertThat(current.has("fallback")).isFalse();
    }

    @Test
    void duringTheTransitionADirectBookingFreezesThePreviousVersionAndTellsTheClient() throws Exception {
        String key = uniqueKey();
        publishLine(key, Duration.ofDays(1));
        String category = uniqueCategory();
        UUID onV1 = seedAgent(category, offerFor(key, 1));

        // The category joins the booking too: the Contract must survive it.
        JsonNode booked = post("/api/tasks/direct", bookBody(onV1, category, contract(key, 2, 1000)));

        assertThat(booked.path("contract").path("template").asText()).isEqualTo(key);
        assertThat(booked.path("contract").path("version").asInt()).isEqualTo(1);
        assertThat(booked.path("contract").path("params").path("max_words").asInt()).isEqualTo(1000);
        assertThat(booked.path("templateFallback").path("usedVersion").asInt()).isEqualTo(1);
        assertThat(booked.path("templateFallback").path("currentVersion").asInt()).isEqualTo(2);

        JsonNode task = get("/api/tasks/" + booked.path("id").asText());
        assertThat(task.path("contract").path("version").asInt()).isEqualTo(1);
        assertThat(task.path("templateFallback").path("usedVersion").asInt()).isEqualTo(1);
    }

    @Test
    void aBookingUnderTheCurrentVersionCarriesNoNotice() throws Exception {
        String key = uniqueKey();
        publishLine(key, Duration.ofDays(1));
        String category = uniqueCategory();
        UUID onV2 = seedAgent(category, offerFor(key, 2));

        JsonNode booked = post("/api/tasks/direct", bookBody(onV2, null, contract(key, 1, 1000)));

        assertThat(booked.path("contract").path("version").asInt()).isEqualTo(2);
        assertThat(booked.has("templateFallback")).isFalse();
        assertThat(get("/api/tasks/" + booked.path("id").asText()).has("templateFallback")).isFalse();
    }

    @Test
    void afterTheTransitionTheOldVersionTakesNoNewContracts() throws Exception {
        String key = uniqueKey();
        publishLine(key, TRANSITION.plusDays(1));
        String category = uniqueCategory();
        UUID onV1 = seedAgent(category, offerFor(key, 1));

        // No fallback any more: the Contract uses v2, which nobody offers.
        JsonNode preview = post("/api/tasks/match-preview", previewBody(category, contract(key, 1, 1000)));
        assertThat(preview.path("shortlist")).isEmpty();
        assertThat(preview.has("fallback")).isFalse();
        assertThat(preview.path("noMatch").path("summary").asText()).contains("does not offer Template " + key + "@2");

        HttpResponse<String> booking = send("POST", "/api/tasks/direct", bookBody(onV1, null, contract(key, 1, 1000)));
        assertThat(booking.statusCode()).as(booking.body()).isEqualTo(400);
        assertThat(booking.body()).contains("does not offer Template " + key + "@2");
    }

    @Test
    void theTemplatesEndpointSaysWhichVersionIsCurrentAndUntilWhenThePreviousOneTakesContracts() throws Exception {
        String during = uniqueKey();
        Instant v2Published = publishLine(during, Duration.ofDays(1));
        String after = uniqueKey();
        publishLine(after, TRANSITION.plusDays(1));

        JsonNode v1 = get("/api/catalogue/templates/" + during + "/1");
        assertThat(v1.path("current").asBoolean()).isFalse();
        assertThat(v1.path("takesNewContracts").asBoolean()).isTrue();
        assertThat(Instant.parse(v1.path("takesNewContractsUntil").asText()))
                .isCloseTo(v2Published.plus(TRANSITION), within());
        JsonNode v2 = get("/api/catalogue/templates/" + during + "/2");
        assertThat(v2.path("current").asBoolean()).isTrue();
        assertThat(v2.path("takesNewContracts").asBoolean()).isTrue();
        assertThat(v2.has("takesNewContractsUntil")).isFalse();

        JsonNode closed = findTemplate(get("/api/catalogue/templates"), after, 1);
        assertThat(closed.path("current").asBoolean()).isFalse();
        assertThat(closed.path("takesNewContracts").asBoolean()).isFalse();
        assertThat(findTemplate(get("/api/catalogue/templates"), "text_basic", 1).path("current").asBoolean()).isTrue();
    }

    @Test
    void anOfferCanNameTheOldVersionDuringTheTransitionButNotAfterItOrARetiredVersion() throws Exception {
        String during = uniqueKey();
        publishLine(during, Duration.ofDays(1));
        String after = uniqueKey();
        publishLine(after, TRANSITION.plusDays(1));
        String retired = uniqueKey();
        insertTemplate(retired, 1, Instant.now().minus(Duration.ofDays(5)), "RETIRED");
        String category = uniqueCategory();
        String agentId = registerListed(category);

        assertThat(publishOffer(agentId, category, during, 1).statusCode()).isEqualTo(200);

        HttpResponse<String> closed = publishOffer(agentId, category, after, 1);
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(400);
        assertThat(mapper.readTree(closed.body()).path("message").asText())
                .contains(after + "@1 takes no new Contracts", "use " + after + "@2");

        HttpResponse<String> retiredOffer = publishOffer(agentId, category, retired, 1);
        assertThat(retiredOffer.statusCode()).as(retiredOffer.body()).isEqualTo(400);
        assertThat(retiredOffer.body()).contains(retired + "@1 takes no new Contracts");
    }

    // ---- helpers ----

    private static String uniqueKey() {
        return "t92_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static org.assertj.core.data.TemporalUnitOffset within() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(1, ChronoUnit.MINUTES);
    }

    /** {@code key}@1 published long ago and {@code key}@2 published {@code v2Age} ago; returns v2's time. */
    private Instant publishLine(String key, Duration v2Age) {
        Instant v2Published = Instant.now().minus(v2Age);
        insertTemplate(key, 1, v2Published.minus(Duration.ofDays(200)), "ACTIVE");
        insertTemplate(key, 2, v2Published, "ACTIVE");
        return v2Published;
    }

    private void insertTemplate(String key, int version, Instant publishedAt, String status) {
        jdbc.update("INSERT INTO contract_templates (template_key, version, status, published_at, definition) "
                + "VALUES (?, ?, ?, ?, ?::jsonb)", key, version, status, Timestamp.from(publishedAt), """
                {"title":"Report v%d","description":"A written report.","outputShape":"TEXT",
                 "checks":["FORMAT_TEXT_NON_EMPTY"],
                 "parameters":[{"name":"max_words","label":"Most words","description":"Upper word count",
                   "comparison":"max","bounds":{"minimum":1,"maximum":10000}}],
                 "expectationMode":"NONE",
                 "rulingSettlement":{"FULFILLED":1,"PARTIALLY_FULFILLED":0.5,"NOT_FULFILLED":0}}"""
                .formatted(version));
    }

    /** The Agent's own text_basic term (direct bookings without terms) plus {@code key}@{@code version}. */
    private static String offerFor(String key, int version) {
        return """
                {"terms":[{"template":"text_basic","version":1,"params":{}},
                 {"template":"%s","version":%d,"params":{"max_words":{"bound":5000}}}]}""".formatted(key, version);
    }

    private static String contract(String key, int version, int maxWords) {
        return """
                {"template":"%s","version":%d,"params":{"max_words":%d}}""".formatted(key, version, maxWords);
    }

    private static String previewBody(String category, String contract) {
        return """
                {"title":"Report","description":"Write a report","category":"%s","budget":10,"contract":%s}"""
                .formatted(category, contract);
    }

    private static String bookBody(UUID agentId, String category, String contract) {
        return """
                {"title":"Report","description":"Write a report","budget":10.00,"agentId":"%s"%s,"contract":%s}"""
                .formatted(agentId, category == null ? "" : ",\"category\":\"" + category + "\"", contract);
    }

    private UUID seedAgent(String category, String offerJson) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Seeded Agent', 'ACTIVE', ?, 50.00)", agentId, ownerId, versionId);
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, offer, "
                        + "capability_categories, webhook_url, max_execution_seconds, price) "
                        + "VALUES (?, ?, 1, '{\"format\":\"TEXT\"}'::jsonb, ?::jsonb, ?, "
                        + "'https://agent.example.com/run', 60, ?)",
                versionId, agentId, offerJson, new String[]{category}, new BigDecimal("10.00"));
        return agentId;
    }

    /** Registers a TEXT agent as the dev user, then activates and lists it by SQL. */
    private String registerListed(String category) throws Exception {
        String agentId = post("/api/agents", """
                {"name":"Transition agent","outputSpec":{"format":"TEXT"},"capabilityCategories":["%s"],
                 "webhookUrl":"https://agent.example.com/run","maxExecutionSeconds":60,"price":5}"""
                .formatted(category)).path("id").asText();
        jdbc.update("UPDATE agents SET status = 'ACTIVE', current_version_id = "
                + "(SELECT id FROM agent_versions WHERE agent_id = ?::uuid) WHERE id = ?::uuid", agentId, agentId);
        jdbc.update("UPDATE agent_profiles SET is_listed = true WHERE agent_id = ?::uuid", agentId);
        return agentId;
    }

    private HttpResponse<String> publishOffer(String agentId, String category, String key, int version)
            throws Exception {
        return send("POST", "/api/agents/" + agentId + "/versions", """
                {"price":12,"maxExecutionSeconds":60,"capabilityCategories":["%s"],"offer":{"terms":[
                  {"template":"text_basic","version":1,"params":{}},
                  {"template":"%s","version":%d,"params":{"max_words":{"bound":5000}}}]}}"""
                .formatted(category, key, version));
    }

    private static JsonNode findTemplate(JsonNode templates, String key, int version) {
        for (JsonNode t : templates) {
            if (t.path("key").asText().equals(key) && t.path("version").asInt() == version) {
                return t;
            }
        }
        throw new AssertionError("no template " + key + "@" + version);
    }

    private static List<String> agentIds(JsonNode options) {
        List<String> ids = new ArrayList<>();
        options.forEach(o -> ids.add(o.path("agentId").asText()));
        return ids;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private JsonNode post(String path, String json) throws Exception {
        HttpResponse<String> response = send("POST", path, json);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
