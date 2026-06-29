package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.routing.info.TaskDispatchPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class AgentDispatchClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DispatchMessage messageTo(String webhookUrl) {
        TaskDispatchPayload payload = new TaskDispatchPayload(
                "Summarise report", "Summarise the attached quarterly report", "summarisation",
                "{\"type\":\"string\"}", "{\"format\":\"JSON\"}", "https://platform.test/api/agent-callbacks/x/result");
        return new DispatchMessage(UUID.randomUUID(), UUID.randomUUID(), webhookUrl, "corr-123", payload);
    }

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void postsSignedBodyToHttpsWebhook() {
        String webhook = "https://agent.example.com/run";
        server.expect(requestTo(webhook))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer the-token"))
                .andExpect(header("X-Correlation-ID", "corr-123"))
                .andExpect(jsonPath("$.category").value("summarisation"))
                .andExpect(jsonPath("$.callbackUrl")
                        .value("https://platform.test/api/agent-callbacks/x/result"))
                .andExpect(jsonPath("$.outputSpec.format").value("JSON"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));

        AgentDispatchClient client = new AgentDispatchClient(builder, objectMapper, false);
        client.dispatch(messageTo(webhook), "the-token");

        server.verify();
    }

    @Test
    void rejectsNonHttpsWebhookInProdProfile() {
        AgentDispatchClient client = new AgentDispatchClient(RestClient.builder(), objectMapper, false);

        assertThatThrownBy(() -> client.dispatch(messageTo("http://agent.example.com/run"), "the-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void allowsHttpLocalhostInDevProfile() {
        String webhook = "http://localhost:9000/run";
        server.expect(requestTo(webhook))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));

        AgentDispatchClient client = new AgentDispatchClient(builder, objectMapper, true);
        client.dispatch(messageTo(webhook), "the-token");

        server.verify();
    }

    @Test
    void rejectsNonLocalhostHttpEvenInDevProfile() {
        AgentDispatchClient client = new AgentDispatchClient(RestClient.builder(), objectMapper, true);

        assertThatThrownBy(() -> client.dispatch(messageTo("http://agent.example.com/run"), "the-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }
}
