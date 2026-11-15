package com.hireai.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #89 (spec #87 S1): when a Builder publishes a version it may edit the Offer, the Template
 * versions it accepts and each parameter's range, within the Templates' bounds. The edited Offer
 * shows on the Builder's agent and the public agent page; one outside the bounds is a 400 that
 * names the parameter and what is allowed, and publishes nothing. Driven over the public HTTP API as
 * the test profile's fixed caller (who owns the agents it registers).
 */
class OfferEditorIntegrationTest extends WholeAppIntegrationTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"total\"]}";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aBuilderPublishesAVersionWithAnEditedOfferThatTheBuilderAndClientsSee() throws Exception {
        String category = uniqueCategory();
        String agentId = registerListed("FILE", null, category);

        JsonNode published = post("/api/agents/" + agentId + "/versions", """
                {"price":12,"maxExecutionSeconds":60,"capabilityCategories":["%s"],
                 "offer":{"terms":[
                   {"template":"file_basic","version":1,
                    "params":{"max_bytes":{"bound":10485760},"media_types":{"oneOf":["application/pdf","image/png"]}}},
                   {"template":"text_basic","version":1,"params":{"max_length":{"bound":2000}}}]}}"""
                .formatted(category));

        JsonNode version = published.path("currentVersion");
        assertThat(version.path("versionNumber").asInt()).isEqualTo(2);
        assertThat(version.path("outputSpec").path("format").asText()).isEqualTo("FILE");
        JsonNode offer = get("/api/agents/" + agentId).path("currentVersion").path("offer");
        assertThat(offer.path("price").decimalValue()).isEqualByComparingTo("12");
        assertThat(offer.path("terms")).extracting(t -> t.path("template").asText() + "@" + t.path("version").asInt())
                .containsExactly("file_basic@1", "text_basic@1");
        JsonNode file = offer.path("terms").get(0).path("params");
        assertThat(file.path("max_bytes").path("bound").asLong()).isEqualTo(10_485_760L);
        assertThat(file.path("media_types").path("oneOf")).extracting(JsonNode::asText)
                .containsExactly("application/pdf", "image/png");

        // A Client sees the same Offer on the public agent page.
        JsonNode publicOffer = get("/api/catalogue/agents/" + agentId).path("offer");
        assertThat(publicOffer.path("terms")).isEqualTo(offer.path("terms"));
        assertThat(publicOffer.path("price").decimalValue()).isEqualByComparingTo("12");

        // Version 1 keeps the Offer it was published with (in-flight tasks are unaffected).
        assertThat(jdbc.queryForObject("SELECT offer->'terms'->0->'params' = '{}'::jsonb FROM agent_versions "
                + "WHERE agent_id = ?::uuid AND version_number = 1", Boolean.class, agentId)).isTrue();

        // Publishing again without an Offer carries the edited one over.
        post("/api/agents/" + agentId + "/versions",
                "{\"price\":13,\"maxExecutionSeconds\":60,\"capabilityCategories\":[\"" + category + "\"]}");
        assertThat(get("/api/agents/" + agentId).path("currentVersion").path("offer").path("terms"))
                .isEqualTo(offer.path("terms"));
    }

    @Test
    void anOfferOutsideTheTemplatesBoundsIsRejectedWithAClearMessageAndPublishesNothing() throws Exception {
        String category = uniqueCategory();
        String agentId = registerListed("FILE", null, category);

        JsonNode tooSmall = rejected(agentId, category, """
                [{"template":"file_basic","version":1,"params":{"max_bytes":{"bound":0}}}]""");
        assertThat(tooSmall.path("message").asText())
                .contains("file_basic@1", "Largest file size (max_bytes)", "between 1 and 104857600", "got 0");

        JsonNode unknownType = rejected(agentId, category, """
                [{"template":"file_basic","version":1,"params":{"media_types":{"oneOf":["video/mp4"]}}}]""");
        assertThat(unknownType.path("message").asText())
                .contains("Accepted file types (media_types)", "application/pdf", "got video/mp4");

        JsonNode unknownParameter = rejected(agentId, category, """
                [{"template":"file_basic","version":1,"params":{"colour":{"any":true}}}]""");
        assertThat(unknownParameter.path("message").asText()).contains("file_basic@1 has no parameter colour");

        JsonNode unknownTemplate = rejected(agentId, category, """
                [{"template":"file_basic","version":1,"params":{}},{"template":"poetry","version":3,"params":{}}]""");
        assertThat(unknownTemplate.path("message").asText()).contains("Unknown Template poetry@3");

        JsonNode badShape = rejected(agentId, category, """
                [{"template":"file_basic","version":1,"params":{"max_bytes":{"bound":"big"}}}]""");
        assertThat(badShape.path("message").asText()).contains("file_basic max_bytes", "{\"bound\":number}");

        JsonNode droppedOwn = rejected(agentId, category, """
                [{"template":"text_basic","version":1,"params":{}}]""");
        assertThat(droppedOwn.path("message").asText()).contains("must keep file_basic@1", "direct bookings");

        assertThat(versionCount(agentId)).isEqualTo(1);
    }

    @Test
    void aJsonAgentMayWidenItsOfferToAnySchemaButMustKeepAcceptingItsOwn() throws Exception {
        String category = uniqueCategory();
        String agentId = registerListed("JSON", SCHEMA, category);

        JsonNode notOwn = rejected(agentId, category, """
                [{"template":"custom_json","version":1,"params":{"schema":{"oneOf":["{\\"type\\":\\"array\\"}"]}}}]""");
        assertThat(notOwn.path("message").asText()).contains("own schema");

        JsonNode unreadable = rejected(agentId, category, """
                [{"template":"custom_json","version":1,"params":{"schema":{"oneOf":[%s,"not a schema"]}}}]"""
                .formatted(mapper.writeValueAsString(SCHEMA)));
        assertThat(unreadable.path("message").asText()).contains("not a valid JSON Schema");
        assertThat(versionCount(agentId)).isEqualTo(1);

        post("/api/agents/" + agentId + "/versions", """
                {"price":9,"maxExecutionSeconds":60,"capabilityCategories":["%s"],
                 "offer":{"terms":[{"template":"custom_json","version":1,"params":{"schema":{"any":true}}}]}}"""
                .formatted(category));
        assertThat(get("/api/agents/" + agentId).path("currentVersion").path("offer").path("terms").get(0)
                .path("params").path("schema").path("any").asBoolean()).isTrue();
    }

    // ---- helpers ----

    /** Registers an agent as the dev user, then activates and lists it by SQL (no webhook handshake needed). */
    private String registerListed(String format, String schema, String category) throws Exception {
        String spec = schema == null
                ? "{\"format\":\"" + format + "\"}"
                : "{\"format\":\"" + format + "\",\"schema\":" + mapper.writeValueAsString(schema) + "}";
        JsonNode registered = post("/api/agents", """
                {"name":"Offer editor agent","outputSpec":%s,"capabilityCategories":["%s"],
                 "webhookUrl":"https://agent.example.com/run","maxExecutionSeconds":60,"price":5}"""
                .formatted(spec, category));
        String agentId = registered.path("id").asText();
        jdbc.update("UPDATE agents SET status = 'ACTIVE', current_version_id = "
                + "(SELECT id FROM agent_versions WHERE agent_id = ?::uuid) WHERE id = ?::uuid", agentId, agentId);
        jdbc.update("UPDATE agent_profiles SET is_listed = true WHERE agent_id = ?::uuid", agentId);
        return agentId;
    }

    private JsonNode rejected(String agentId, String category, String termsJson) throws Exception {
        HttpResponse<String> response = send("POST", "/api/agents/" + agentId + "/versions", """
                {"price":12,"maxExecutionSeconds":60,"capabilityCategories":["%s"],"offer":{"terms":%s}}"""
                .formatted(category, termsJson));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        return mapper.readTree(response.body());
    }

    private int versionCount(String agentId) {
        return jdbc.queryForObject("SELECT count(*) FROM agent_versions WHERE agent_id = ?::uuid",
                Integer.class, agentId);
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
