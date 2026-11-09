package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.routing.info.TaskDispatchPayload;
import com.hireai.infrastructure.webhook.HostResolver;
import com.hireai.infrastructure.webhook.WebhookUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class AgentDispatchClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DispatchMessage messageTo(String webhookUrl) {
        TaskDispatchPayload payload = new TaskDispatchPayload(
                "Summarise report", "Summarise the attached quarterly report", "summarisation",
                "{\"format\":\"JSON\"}", java.time.Instant.parse("2026-11-10T12:00:00.123456Z"),
                "https://platform.test/api/agent-callbacks/x/result");
        return new DispatchMessage(UUID.randomUUID(), UUID.randomUUID(), webhookUrl, "corr-123", payload);
    }

    /** Every name resolves to a public address unless a test says otherwise. */
    private static final HostResolver PUBLIC_DNS = host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")};

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
                .andExpect(jsonPath("$.executionDeadline").value("2026-11-10T12:00:00.123456Z"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));

        AgentDispatchClient client = client(builder.build(), PUBLIC_DNS, false);
        client.dispatch(messageTo(webhook), "the-token");

        server.verify();
    }

    @Test
    void rejectsNonHttpsWebhookInProdProfile() {
        AgentDispatchClient client = client(RestClient.builder().build(), PUBLIC_DNS, false);

        assertThatThrownBy(() -> client.dispatch(messageTo("http://agent.example.com/run"), "the-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void allowsHttpLocalhostInDevProfile() {
        String webhook = "http://localhost:9000/run";
        server.expect(requestTo(webhook))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));

        AgentDispatchClient client = client(builder.build(), PUBLIC_DNS, true);
        client.dispatch(messageTo(webhook), "the-token");

        server.verify();
    }

    @Test
    void rejectsNonLocalhostHttpEvenInDevProfile() {
        AgentDispatchClient client = client(RestClient.builder().build(), PUBLIC_DNS, true);

        assertThatThrownBy(() -> client.dispatch(messageTo("http://agent.example.com/run"), "the-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void aNon2xxReplyIsAnAgentSendFailureCarryingTheStatus() {
        String webhook = "https://agent.example.com/run";
        server.expect(requestTo(webhook)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        AgentDispatchClient client = client(builder.build(), PUBLIC_DNS, false);

        assertThatThrownBy(() -> client.dispatch(messageTo(webhook), "the-token"))
                .isInstanceOfSatisfying(AgentSendFailedException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.statusCode()).hasValue(500));
    }

    @Test
    void anUnreachableOrTimedOutEndpointIsAnAgentSendFailureWithoutAStatus() {
        String webhook = "https://agent.example.com/run";
        server.expect(requestTo(webhook)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));
        AgentDispatchClient client = client(builder.build(), PUBLIC_DNS, false);

        assertThatThrownBy(() -> client.dispatch(messageTo(webhook), "the-token"))
                .isInstanceOfSatisfying(AgentSendFailedException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.statusCode()).isEmpty());
    }

    @Test
    void aHostThatNowResolvesToAPrivateAddressIsNeverContacted() {
        // No expectation registered: any request reaching the server fails verify().
        HostResolver rebound = host -> new InetAddress[]{InetAddress.getByName("10.0.0.7")};
        AgentDispatchClient client = client(builder.build(), rebound, false);

        assertThatThrownBy(() -> client.dispatch(messageTo("https://agent.example.com/run"), "the-token"))
                .isInstanceOfSatisfying(AgentSendFailedException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.statusCode()).isEmpty();
                    org.assertj.core.api.Assertions.assertThat(e).hasMessageContaining("private/blocked");
                });
        server.verify();
    }

    @Test
    void aRedirectReplyIsAFailedSendCarryingItsStatus() {
        String webhook = "https://agent.example.com/run";
        server.expect(requestTo(webhook)).andRespond(withStatus(HttpStatus.FOUND)
                .location(java.net.URI.create("https://169.254.169.254/latest/meta-data")));
        AgentDispatchClient client = client(builder.build(), PUBLIC_DNS, false);

        assertThatThrownBy(() -> client.dispatch(messageTo(webhook), "the-token"))
                .isInstanceOfSatisfying(AgentSendFailedException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.statusCode()).hasValue(302));
        server.verify();
    }

    private AgentDispatchClient client(RestClient restClient, HostResolver resolver, boolean allowInsecureLocalhost) {
        return new AgentDispatchClient(restClient, objectMapper,
                new WebhookUrlValidator(false, allowInsecureLocalhost, resolver), allowInsecureLocalhost);
    }
}
