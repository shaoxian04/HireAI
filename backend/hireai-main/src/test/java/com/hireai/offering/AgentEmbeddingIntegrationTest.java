package com.hireai.offering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.embedding.FakeEmbeddingProvider;
import com.hireai.support.harness.WholeAppIntegrationTest;
import com.hireai.utility.hash.Sha256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #84: an Agent's profile text (name, tagline, description) is embedded on registration and on
 * every profile edit, through the real OpenAI client against the {@link FakeEmbeddingProvider},
 * and stored in pgvector tagged with the model and the text hash. A provider outage never fails
 * the Builder's request.
 */
class AgentEmbeddingIntegrationTest extends WholeAppIntegrationTest {

    private static final String MODEL = "text-embedding-3-small";

    private final FakeEmbeddingProvider provider = FakeEmbeddingProvider.shared();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void resetProvider() {
        provider.reset();
    }

    @AfterEach
    void restoreProvider() {
        provider.reset();
    }

    @Test
    void registeringAnAgentStoresAVectorTaggedWithTheModelAndTextHash() throws Exception {
        provider.vectorFor(text -> FakeEmbeddingProvider.constant(0.25f));

        UUID agentId = register("Contract Clause Extractor");

        assertThat(provider.requests()).extracting(FakeEmbeddingProvider.Recorded::input)
                .containsExactly("Contract Clause Extractor");
        assertThat(provider.requests().get(0).model()).isEqualTo(MODEL);
        assertThat(provider.requests().get(0).authorization()).isEqualTo("Bearer test-embedding-key");
        Map<String, Object> row = embeddingRow(agentId);
        assertThat(row.get("model")).isEqualTo(MODEL);
        assertThat(row.get("text_hash")).isEqualTo(Sha256.hex("Contract Clause Extractor"));
        assertThat(row.get("dims")).isEqualTo(1536);
        assertThat(row.get("first")).isEqualTo(0.25d);
    }

    @Test
    void editingTheProfileReEmbedsNameTaglineAndDescription() throws Exception {
        UUID agentId = register("Summariser");
        provider.reset();

        updateProfile(agentId, "Legal summaries", "Summarises contracts and court rulings.");

        String expectedText = "Summariser\nLegal summaries\nSummarises contracts and court rulings.";
        assertThat(provider.requests()).extracting(FakeEmbeddingProvider.Recorded::input)
                .containsExactly(expectedText);
        assertThat(embeddingRow(agentId).get("text_hash")).isEqualTo(Sha256.hex(expectedText));
    }

    @Test
    void anUnchangedProfileIsNotEmbeddedAgain() throws Exception {
        UUID agentId = register("Translator");
        updateProfile(agentId, "French to English", "Translates documents.");
        provider.reset();

        updateProfile(agentId, "French to English", "Translates documents.");

        assertThat(provider.requests()).isEmpty();
    }

    @Test
    void registrationAndProfileEditsSucceedWhileTheProviderIsDown() throws Exception {
        provider.down();

        UUID agentId = register("Offline Registrant");
        updateProfile(agentId, "Still saved", "The edit is kept without a vector.");

        assertThat(provider.requests()).hasSize(2);
        assertThat(embeddingCount(agentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT description FROM agent_profiles WHERE agent_id = ?",
                String.class, agentId)).isEqualTo("The edit is kept without a vector.");
    }

    @Test
    void aHangingProviderTimesOutAndTheEditStillSucceeds() throws Exception {
        UUID agentId = register("Patient Agent");
        String hashBefore = (String) embeddingRow(agentId).get("text_hash");
        provider.hanging();

        long started = System.nanoTime();
        updateProfile(agentId, "New tagline", "New description");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(10_000);
        assertThat(embeddingRow(agentId).get("text_hash")).isEqualTo(hashBefore);
    }

    private UUID register(String name) throws Exception {
        JsonNode registered = send("POST", "/api/agents", """
                {"name":"%s","outputSpec":{"format":"JSON","schema":"{}","acceptanceCriteria":"ok"},
                 "capabilityCategories":["%s"],"webhookUrl":"https://agent.example.com/hook",
                 "maxExecutionSeconds":60,"price":5.00}
                """.formatted(name, uniqueCategory()));
        return UUID.fromString(registered.path("id").asText());
    }

    private void updateProfile(UUID agentId, String tagline, String description) throws Exception {
        send("PUT", "/api/agents/" + agentId + "/profile", mapper.writeValueAsString(Map.of(
                "tagline", tagline, "description", description, "isListed", false)));
    }

    private Map<String, Object> embeddingRow(UUID agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT model, text_hash, vector_dims(embedding) AS dims, "
                        + "(embedding::real[])[1]::float8 AS first FROM agent_embeddings WHERE agent_id = ?",
                agentId);
        assertThat(rows).as("embedding row for " + agentId).hasSize(1);
        return rows.get(0);
    }

    private int embeddingCount(UUID agentId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_embeddings WHERE agent_id = ?",
                Integer.class, agentId);
        return count == null ? 0 : count;
    }

    private JsonNode send(String method, String path, String json) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(platformUrl(path)))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path + " " + response.body()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }
}
