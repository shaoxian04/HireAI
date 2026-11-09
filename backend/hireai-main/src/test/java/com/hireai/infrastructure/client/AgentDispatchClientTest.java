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
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class AgentDispatchClientTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:30:00Z");

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
        client.dispatch(messageTo(webhook), "the-token", null);

        server.verify();
    }

    @Test
    void rejectsNonHttpsWebhookInProdProfile() {
        AgentDispatchClient client = client(RestClient.builder().build(), PUBLIC_DNS, false);

        assertThatThrownBy(() -> client.dispatch(messageTo("http://agent.example.com/run"), "the-token", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void allowsHttpLocalhostInDevProfile() {
        String webhook = "http://localhost:9000/run";
        server.expect(requestTo(webhook))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));

        AgentDispatchClient client = client(builder.build(), PUBLIC_DNS, true);
        client.dispatch(messageTo(webhook), "the-token", null);

        server.verify();
    }

    @Test
    void rejectsNonLocalhostHttpEvenInDevProfile() {
        AgentDispatchClient client = client(RestClient.builder().build(), PUBLIC_DNS, true);

        assertThatThrownBy(() -> client.dispatch(messageTo("http://agent.example.com/run"), "the-token", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void aNon2xxReplyIsAnAgentSendFailureCarryingTheStatus() {
        String webhook = "https://agent.example.com/run";
        server.expect(requestTo(webhook)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        AgentDispatchClient client = client(builder.build(), PUBLIC_DNS, false);

        assertThatThrownBy(() -> client.dispatch(messageTo(webhook), "the-token", null))
                .isInstanceOfSatisfying(AgentSendFailedException.class,
                        e -> {
                            org.assertj.core.api.Assertions.assertThat(e.statusCode()).hasValue(500);
                            org.assertj.core.api.Assertions.assertThat(e).isNotInstanceOf(AgentBusyException.class);
                        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {429, 503})
    void a429Or503ReplyIsABusyDecline(int status) {
        String webhook = "https://agent.example.com/run";
        server.expect(requestTo(webhook)).andRespond(withStatus(HttpStatus.valueOf(status)));
        AgentDispatchClient client = client(builder.build(), PUBLIC_DNS, false);

        assertThatThrownBy(() -> client.dispatch(messageTo(webhook), "the-token", null))
                .isInstanceOfSatisfying(AgentBusyException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.statusCode()).hasValue(status));
    }

    @Test
    void anUnreachableOrTimedOutEndpointIsAnAgentSendFailureWithoutAStatus() {
        String webhook = "https://agent.example.com/run";
        server.expect(requestTo(webhook)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));
        AgentDispatchClient client = client(builder.build(), PUBLIC_DNS, false);

        assertThatThrownBy(() -> client.dispatch(messageTo(webhook), "the-token", null))
                .isInstanceOfSatisfying(AgentSendFailedException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.statusCode()).isEmpty());
    }

    @Test
    void aHostThatNowResolvesToAPrivateAddressIsNeverContacted() {
        // No expectation registered: any request reaching the server fails verify().
        HostResolver rebound = host -> new InetAddress[]{InetAddress.getByName("10.0.0.7")};
        AgentDispatchClient client = client(builder.build(), rebound, false);

        assertThatThrownBy(() -> client.dispatch(messageTo("https://agent.example.com/run"), "the-token", null))
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

        assertThatThrownBy(() -> client.dispatch(messageTo(webhook), "the-token", null))
                .isInstanceOfSatisfying(AgentSendFailedException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.statusCode()).hasValue(302));
        server.verify();
    }

    @Test
    void anAgentWithASecretGetsTheExactBytesSentSignedWithTheTimestampAndSecret() {
        String webhook = "https://agent.example.com/run";
        AtomicReference<byte[]> sentBody = new AtomicReference<>();
        AtomicReference<String> sentSignature = new AtomicReference<>();
        server.expect(requestTo(webhook))
                .andExpect(header("Authorization", "Bearer the-token"))
                .andExpect(header("X-Correlation-ID", "corr-123"))
                .andExpect(request -> {
                    sentBody.set(((MockClientHttpRequest) request).getBodyAsBytes());
                    sentSignature.set(request.getHeaders().getFirst("X-HireAI-Signature"));
                })
                .andRespond(withStatus(HttpStatus.OK));
        AgentDispatchClient client = client(builder.build(), PUBLIC_DNS, false);

        client.dispatch(messageTo(webhook), "the-token", "agsec_test-secret");

        server.verify();
        assertThat(sentSignature.get()).isEqualTo("t=" + NOW.getEpochSecond() + ",v1="
                + independentHmacHex("agsec_test-secret", NOW.getEpochSecond() + "." , sentBody.get()));
    }

    @Test
    void anAgentWithoutASecretGetsAnUnsignedWorkOrder() {
        String webhook = "https://agent.example.com/run";
        server.expect(requestTo(webhook))
                .andExpect(header("Authorization", "Bearer the-token"))
                .andExpect(headerDoesNotExist("X-HireAI-Signature"))
                .andRespond(withStatus(HttpStatus.OK));
        AgentDispatchClient client = client(builder.build(), PUBLIC_DNS, false);

        client.dispatch(messageTo(webhook), "the-token", null);

        server.verify();
    }

    /** HMAC-SHA256 over prefix bytes + body bytes, computed without the production helper. */
    static String independentHmacHex(String secret, String prefix, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(prefix.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AgentDispatchClient client(RestClient restClient, HostResolver resolver, boolean allowInsecureLocalhost) {
        return new AgentDispatchClient(restClient, objectMapper,
                new WebhookUrlValidator(false, allowInsecureLocalhost, resolver), allowInsecureLocalhost,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
