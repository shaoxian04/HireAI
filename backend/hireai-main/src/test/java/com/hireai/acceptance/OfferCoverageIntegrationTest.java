package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.extraction.FakeExtractionProvider;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * S2 of the acceptance-contract spec (#91, D34/D35): matching keeps only Agents whose Offer covers the
 * Contract, on the shortlist, direct booking and routed selection alike, and an empty result says which
 * parameter excluded the Agents, with relax suggestions. Driven over the public HTTP API.
 */
class OfferCoverageIntegrationTest extends WholeAppIntegrationTest {

    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String FILE_SPEC = "{\"format\":\"FILE\"}";
    /** file_basic@1: the Agent accepts files of at least {@code minBytes}, of these media types. */
    private static final String PNG_OFFER = fileOffer(1000, "\"image/png\"");
    private static final String PDF_OFFER = fileOffer(1000, "\"application/pdf\"");
    private static final String PNG_OVER_10K_OFFER = fileOffer(10_000, "\"image/png\"");

    @Autowired TaskReliabilityAppService taskReliabilityAppService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final FakeExtractionProvider llm = FakeExtractionProvider.shared();

    @BeforeEach
    void fundDevUser() {
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 1000.00, 0.00) "
                + "ON CONFLICT (user_id) DO UPDATE SET available_balance = wallets.available_balance + 1000.00",
                UUID.randomUUID(), DEV_USER);
    }

    @Test
    void theShortlistListsOnlyAgentsWhoseOfferCoversTheContract() throws Exception {
        String category = uniqueCategory();
        UUID png = seedAgent(category, "https://agent.example.com/png", FILE_SPEC, PNG_OFFER, "80.00");
        seedAgent(category, "https://agent.example.com/pdf", FILE_SPEC, PDF_OFFER, "90.00");
        seedAgent(category, "https://agent.example.com/plain", FILE_SPEC, null, "95.00"); // migrated: names no param

        JsonNode preview = post("/api/tasks/match-preview", previewBody(category, 10, pngUnder(5000)));

        assertThat(agentIds(preview.path("shortlist"))).containsExactly(png.toString());
        assertThat(preview.has("noMatch")).isFalse();

        // Without a Contract the preview filters no Offer, as before.
        JsonNode open = post("/api/tasks/match-preview", previewBody(category, 10, null));
        assertThat(open.path("shortlist")).hasSize(3);
    }

    /**
     * The web with no Output terms chosen (Chrome E2E): the draft is read against the text default, so its
     * extracted terms are text_basic's. The platform never rules an Agent out by a Template the Client didn't
     * choose: the web searches and books on each Agent's own terms, and the JSON Agent the description asks
     * for is found and booked under its own JSON terms.
     */
    @Test
    void withNoTemplateChosenAJsonDescriptionFindsTheJsonAgentOnItsOwnTerms() throws Exception {
        String category = uniqueCategory();
        UUID json = seedAgent(category, "https://agent.example.com/json",
                jsonSpec("{\"type\":\"object\"}"), null, "80.00");
        llm.answering("""
                {"requirements":[
                  {"text":"under 300 characters","check":"TEXT_MAX_LENGTH","params":{"min_length":null,"max_length":300}}
                ]}""");
        try {
            JsonNode draft = post("/api/contracts/draft", """
                    {"title":"Totals","description":"Return the totals as JSON, under 300 characters."}""");
            assertThat(draft.path("contract").path("template").asText()).isEqualTo("text_basic");
            assertThat(draft.path("contract").path("params").path("max_length").asInt()).isEqualTo(300);
        } finally {
            llm.reset();
        }

        // What the web sends then: no contract, so each Agent is matched on its own terms.
        JsonNode preview = post("/api/tasks/match-preview", previewBody(category, 10, null));
        assertThat(agentIds(preview.path("shortlist"))).containsExactly(json.toString());

        JsonNode task = post("/api/tasks/direct", """
                {"title":"Totals","description":"Return the totals as JSON, under 300 characters.",
                 "budget":10.00,"agentId":"%s"}""".formatted(json));
        assertThat(task.path("contract").path("template").asText()).isEqualTo("custom_json");
    }

    @Test
    void nearMissesAboveBudgetMustCoverTheContractToo() throws Exception {
        String category = uniqueCategory();
        UUID pricedPng = seedAgent(category, "https://agent.example.com/png", FILE_SPEC, PNG_OFFER, "50.00", "25.00");
        seedAgent(category, "https://agent.example.com/pdf", FILE_SPEC, PDF_OFFER, "50.00", "20.00");

        JsonNode preview = post("/api/tasks/match-preview", previewBody(category, 10, pngUnder(5000)));

        assertThat(preview.path("shortlist")).isEmpty();
        assertThat(agentIds(preview.path("nearMisses"))).containsExactly(pricedPng.toString());
    }

    @Test
    void anEmptyShortlistNamesTheBlockingParameterWithRelaxSuggestions() throws Exception {
        String category = uniqueCategory();
        seedAgent(category, "https://agent.example.com/a", FILE_SPEC, PDF_OFFER, "80.00");
        seedAgent(category, "https://agent.example.com/b", FILE_SPEC, PDF_OFFER, "80.00");
        seedAgent(category, "https://agent.example.com/c", FILE_SPEC, PNG_OVER_10K_OFFER, "80.00");
        seedAgent(category, "https://agent.example.com/d", "{\"format\":\"TEXT\"}", null, "80.00");

        JsonNode noMatch = post("/api/tasks/match-preview", previewBody(category, 10, pngUnder(5000)))
                .path("noMatch");

        assertThat(exclusions(noMatch)).containsExactly("PARAMETER:media_types:2", "PARAMETER:max_bytes:1",
                "TEMPLATE:null:1");
        assertThat(suggestions(noMatch)).containsExactly("media_types:2", "max_bytes:1");
        assertThat(noMatch.path("summary").asText()).isEqualTo("No eligible Agent: parameter media_types "
                + "excluded 2 Agents; parameter max_bytes excluded 1 Agent; 1 Agent does not offer Template file_basic@1.");

        // Following a suggestion is the Client's move: leaving media_types unset finds the two PDF Agents.
        JsonNode relaxed = post("/api/tasks/match-preview", previewBody(category, 10, """
                {"template":"file_basic","version":1,"params":{"max_bytes":5000}}"""));
        assertThat(relaxed.path("shortlist")).hasSize(2);
    }

    @Test
    void aContractOutsideItsTemplatesBoundsIsRejected() throws Exception {
        HttpResponse<String> response = send("POST", "/api/tasks/match-preview", previewBody(uniqueCategory(), 10, """
                {"template":"file_basic","version":1,"params":{"media_types":["video/mp4"]}}"""));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void aDirectBookingUnderTheClientsContractNeedsAnOfferThatCoversItAndFreezesIt() throws Exception {
        String category = uniqueCategory();
        UUID png = seedAgent(category, "https://agent.example.com/png", FILE_SPEC, PNG_OFFER, "80.00");
        UUID pdf = seedAgent(category, "https://agent.example.com/pdf", FILE_SPEC, PDF_OFFER, "80.00");

        HttpResponse<String> refused = send("POST", "/api/tasks/direct", bookBody(pdf, pngUnder(5000)));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("media_types");

        JsonNode booked = post("/api/tasks/direct", bookBody(png, pngUnder(5000)));
        JsonNode contract = get("/api/tasks/" + booked.path("id").asText()).path("contract");
        assertThat(contract.path("template").asText()).isEqualTo("file_basic");
        assertThat(contract.path("params").path("max_bytes").asInt()).isEqualTo(5000);
        assertThat(contract.path("params").path("media_types")).extracting(JsonNode::asText)
                .containsExactly("image/png");
    }

    @Test
    void routingSkipsAHigherRankedAgentWhoseOfferDoesNotCoverTheContract() throws Exception {
        String category = uniqueCategory();
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        String wanted = "{\"type\":\"object\",\"required\":[\"total\"]}";
        seedAgent(category, agent.webhookUrl(), jsonSpec("{\"type\":\"array\"}"), null, "99.00");
        UUID covering = seedAgent(category, agent.webhookUrl(), jsonSpec(wanted), null, "10.00");

        String taskId = submitRouted(category, wanted);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(get("/api/tasks/" + taskId).path("agentId").asText()).isEqualTo(covering.toString()));
    }

    @Test
    void aRoutedTaskNoOfferCoversWaitsAndEndsNoEligibleAgentNamingTheBlockingParameter() throws Exception {
        String category = uniqueCategory();
        seedAgent(category, "https://agent.example.com/a", jsonSpec("{\"type\":\"array\"}"), null, "80.00");
        seedAgent(category, "https://agent.example.com/b", jsonSpec("{\"type\":\"string\"}"), null, "80.00");

        String taskId = submitRouted(category, "{\"type\":\"object\"}");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(get("/api/tasks/" + taskId).path("status").asText()).isEqualTo("AWAITING_AGENT"));
        assertThat(get("/api/tasks/" + taskId).path("failureDetail").asText())
                .isEqualTo("No eligible Agent: parameter schema excluded 2 Agents.");

        // The re-match sweeper's attempts run out: NO_ELIGIBLE_AGENT, refunded, the detail kept.
        UUID id = UUID.fromString(taskId);
        taskReliabilityAppService.rematchOne(id);
        taskReliabilityAppService.rematchOne(id);

        JsonNode task = get("/api/tasks/" + taskId);
        assertThat(task.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(task.path("failureReason").asText()).isEqualTo("NO_ELIGIBLE_AGENT");
        assertThat(task.path("failureDetail").asText()).isEqualTo("No eligible Agent: parameter schema excluded 2 Agents.");
    }

    // ---- helpers ----

    private static String fileOffer(int minBytes, String mediaTypes) {
        return """
                {"terms":[{"template":"file_basic","version":1,"params":{"max_bytes":{"bound":%d},\
                "media_types":{"oneOf":[%s]}}}]}""".formatted(minBytes, mediaTypes);
    }

    private static String pngUnder(int bytes) {
        return """
                {"template":"file_basic","version":1,"params":{"max_bytes":%d,"media_types":["image/png"]}}"""
                .formatted(bytes);
    }

    private String jsonSpec(String schema) throws Exception {
        return "{\"format\":\"JSON\",\"schema\":" + mapper.writeValueAsString(schema) + "}";
    }

    private static String previewBody(String category, int budget, String contract) {
        return """
                {"title":"Logo","description":"Draw a logo","category":"%s","budget":%d%s}"""
                .formatted(category, budget, contract == null ? "" : ",\"contract\":" + contract);
    }

    private static String bookBody(UUID agentId, String contract) {
        return """
                {"title":"Logo","description":"Draw a logo","budget":10.00,"agentId":"%s","contract":%s}"""
                .formatted(agentId, contract);
    }

    private String submitRouted(String category, String schema) throws Exception {
        return post("/api/tasks", """
                {"title":"Extract totals","description":"Pull the totals","category":"%s","budget":20.00,
                 "outputSpec":{"format":"JSON","schema":%s}}""".formatted(category, mapper.writeValueAsString(schema)))
                .path("id").asText();
    }

    private UUID seedAgent(String category, String webhookUrl, String outputSpecJson, String offerJson,
                           String reputation) {
        return seedAgent(category, webhookUrl, outputSpecJson, offerJson, reputation, "10.00");
    }

    /** {@code offerJson} null = the database derives the Offer from the output spec, like the backfill. */
    private UUID seedAgent(String category, String webhookUrl, String outputSpecJson, String offerJson,
                           String reputation, String price) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, 'Seeded Agent', 'ACTIVE', ?, ?)", agentId, ownerId, versionId, new BigDecimal(reputation));
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed) VALUES (?, true)", agentId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, offer, "
                        + "capability_categories, webhook_url, max_execution_seconds, price) "
                        + "VALUES (?, ?, 1, ?::jsonb, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, outputSpecJson, offerJson, new String[]{category}, webhookUrl, new BigDecimal(price));
        return agentId;
    }

    private static List<String> agentIds(JsonNode options) {
        List<String> ids = new ArrayList<>();
        options.forEach(o -> ids.add(o.path("agentId").asText()));
        return ids;
    }

    private static List<String> exclusions(JsonNode noMatch) {
        List<String> out = new ArrayList<>();
        noMatch.path("exclusions").forEach(e -> out.add(e.path("blocker").asText() + ":"
                + (e.path("parameter").isMissingNode() || e.path("parameter").isNull() ? "null"
                : e.path("parameter").asText()) + ":" + e.path("agents").asInt()));
        return out;
    }

    private static List<String> suggestions(JsonNode noMatch) {
        List<String> out = new ArrayList<>();
        noMatch.path("suggestions").forEach(s -> out.add(s.path("parameter").asText() + ":" + s.path("agents").asInt()));
        return out;
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
