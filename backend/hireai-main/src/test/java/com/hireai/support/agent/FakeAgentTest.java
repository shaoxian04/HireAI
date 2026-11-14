package com.hireai.support.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fake Agent's own contract, exercised over real HTTP without Spring or Docker. The platform
 * side is played by a plain JDK client (sending WorkOrders) and a {@link CallbackReceiver}
 * (receiving result callbacks).
 */
class FakeAgentTest {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final FakeAgent agent = FakeAgent.start();
    private CallbackReceiver receiver;

    @AfterEach
    void tearDown() {
        agent.close();
        if (receiver != null) {
            receiver.close();
        }
    }

    private HttpResponse<String> sendWorkOrder(String body, String... headers) throws Exception {
        return sendWorkOrder(body, Duration.ofSeconds(10), headers);
    }

    private HttpResponse<String> sendWorkOrder(String body, Duration timeout, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(agent.webhookUrl()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String workOrderBody(String callbackUrl) {
        return "{\"taskId\":\"t-1\",\"callbackUrl\":\"" + callbackUrl + "\"}";
    }

    @Test
    void recordsEachWorkOrdersHeadersAndExactBodyBytesInArrivalOrder() throws Exception {
        String first = "{\"taskId\":\"a\",  \"note\":\"café\"}";
        String second = "{\"taskId\":\"b\"}";

        sendWorkOrder(first, "Authorization", "Bearer tok-a", "X-Correlation-ID", "corr-a");
        sendWorkOrder(second, "Authorization", "Bearer tok-b");

        List<RecordedWorkOrder> orders = agent.workOrders();
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).body()).isEqualTo(first.getBytes(StandardCharsets.UTF_8));
        assertThat(orders.get(0).header("x-correlation-id")).isEqualTo("corr-a");
        assertThat(orders.get(0).bearerToken()).isEqualTo("tok-a");
        assertThat(orders.get(1).bodyText()).isEqualTo(second);
        assertThat(orders.get(1).bearerToken()).isEqualTo("tok-b");
        assertThat(orders.get(1).header("X-Correlation-ID")).isNull();
    }

    @Test
    void repliesWithTheScriptedStatusAndBody() throws Exception {
        agent.replyWith(FakeAgentReply.status(503).withBody("busy"));

        HttpResponse<String> response = sendWorkOrder("{}");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("busy");
    }

    @Test
    void oneOffRepliesAreUsedInOrderBeforeTheDefault() throws Exception {
        agent.replyWith(FakeAgentReply.status(202))
                .replyOnceWith(FakeAgentReply.status(429))
                .replyOnceWith(FakeAgentReply.status(500));

        assertThat(sendWorkOrder("{}").statusCode()).isEqualTo(429);
        assertThat(sendWorkOrder("{}").statusCode()).isEqualTo(500);
        assertThat(sendWorkOrder("{}").statusCode()).isEqualTo(202);
    }

    @Test
    void repliesOkByDefault() throws Exception {
        assertThat(sendWorkOrder("{}").statusCode()).isEqualTo(200);
    }

    @Test
    void delaysTheReply() throws Exception {
        agent.replyWith(FakeAgentReply.status(202).afterDelay(Duration.ofMillis(400)));

        long start = System.nanoTime();
        HttpResponse<String> response = sendWorkOrder("{}");

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(400));
    }

    @Test
    void aHangingReplyNeverArrivesButIsStillRecordedAndDoesNotBlockOtherOrders() throws Exception {
        agent.replyOnceWith(FakeAgentReply.hang());

        Thread hung = new Thread(() -> {
            try {
                sendWorkOrder("{\"taskId\":\"hung\"}", Duration.ofSeconds(30));
            } catch (Exception ignored) {
                // released at close
            }
        });
        hung.start();

        agent.awaitWorkOrders(1, Duration.ofSeconds(5));
        HttpResponse<String> second = sendWorkOrder("{\"taskId\":\"next\"}", Duration.ofSeconds(2));

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(hung.isAlive()).isTrue();
        agent.releaseHangs();
        hung.join(5_000);
        assertThat(hung.isAlive()).isFalse();
    }

    @Test
    void aLateCallbackPostsACompletedResultWithTheOrdersTokenAfterReplying() throws Exception {
        receiver = CallbackReceiver.start(200);
        agent.replyWith(FakeAgentReply.status(202).withCallback(AgentCallback.completed()));

        HttpResponse<String> reply = sendWorkOrder(workOrderBody(receiver.url()), "Authorization", "Bearer tok-1");

        assertThat(reply.statusCode()).isEqualTo(202);
        List<CallbackOutcome> outcomes = agent.awaitCallbacks(1, Duration.ofSeconds(5));
        assertThat(outcomes.get(0).status()).isEqualTo(200);
        assertThat(outcomes.get(0).sentBeforeReply()).isFalse();
        CallbackReceiver.Received received = receiver.received().get(0);
        assertThat(received.header("Authorization")).isEqualTo("Bearer tok-1");
        assertThat(received.body()).contains("\"agentStatus\":\"COMPLETED\"");
    }

    @Test
    void aCallbackIsSignedOverItsFinalBodyWithTheAgentsSecretUnlessToldOtherwise() throws Exception {
        receiver = CallbackReceiver.start(200);
        String altered = "{\"agentStatus\":\"FAILED\"}";
        agent.replyOnceWith(FakeAgentReply.status(202).withCallback(AgentCallback.completed().withBody(altered)))
                .replyOnceWith(FakeAgentReply.status(202).withCallback(AgentCallback.completed().unsigned()));

        sendWorkOrder(workOrderBody(receiver.url()), "Authorization", "Bearer tok-1");
        agent.awaitCallbacks(1, Duration.ofSeconds(5));
        sendWorkOrder(workOrderBody(receiver.url()), "Authorization", "Bearer tok-2");
        agent.awaitCallbacks(2, Duration.ofSeconds(5));

        assertThat(agent.signingSecret()).matches("agsec_[0-9a-f]{64}");
        String signature = receiver.received().get(0).header("X-HireAI-Signature");
        assertThat(signature).matches("t=\\d+,v1=[0-9a-f]{64}");
        long ts = Long.parseLong(signature.substring(2, signature.indexOf(',')));
        assertThat(signature).isEqualTo(AgentCallback.signature(agent.signingSecret(),
                java.time.Instant.ofEpochSecond(ts), altered.getBytes(StandardCharsets.UTF_8)));
        assertThat(receiver.received().get(1).header("X-HireAI-Signature")).isNull();
    }

    @Test
    void anEarlyCallbackIsSentBeforeTheReply() throws Exception {
        receiver = CallbackReceiver.start(200);
        agent.replyWith(FakeAgentReply.status(202).withCallback(AgentCallback.completed().beforeReply()));

        sendWorkOrder(workOrderBody(receiver.url()), "Authorization", "Bearer tok-1");

        // Sent synchronously inside the handler: already recorded when the reply arrives.
        assertThat(agent.callbacks()).hasSize(1);
        assertThat(agent.callbacks().get(0).sentBeforeReply()).isTrue();
        assertThat(receiver.received()).hasSize(1);
    }

    @Test
    void theTestControlsTheCallbackBodyHeadersAndDelay() throws Exception {
        receiver = CallbackReceiver.start(401);
        agent.replyWith(FakeAgentReply.status(202).withCallback(AgentCallback.completed()
                .withBody("{\"agentStatus\":\"FAILED\"}")
                .withHeader("Authorization", "Bearer forged")
                .withHeader("X-HireAI-Signature", "t=1,v1=00")
                .afterReply(Duration.ofMillis(300))));

        sendWorkOrder(workOrderBody(receiver.url()), "Authorization", "Bearer tok-1");

        assertThat(agent.callbacks()).isEmpty();
        CallbackOutcome outcome = agent.awaitCallbacks(1, Duration.ofSeconds(5)).get(0);
        assertThat(outcome.status()).isEqualTo(401);
        CallbackReceiver.Received received = receiver.received().get(0);
        assertThat(received.header("Authorization")).isEqualTo("Bearer forged");
        assertThat(received.header("X-HireAI-Signature")).isEqualTo("t=1,v1=00");
        assertThat(received.body()).isEqualTo("{\"agentStatus\":\"FAILED\"}");
    }

    @Test
    void aCustomCallbackIsBuiltFromTheRecordedWorkOrder() throws Exception {
        receiver = CallbackReceiver.start(200);
        agent.replyWith(FakeAgentReply.status(202).withCallback(AgentCallback.custom(order ->
                CallbackRequest.post(order.callbackUrl())
                        .header("X-Echo", order.bearerToken())
                        .body("{\"len\":" + order.body().length + "}"))));

        String body = workOrderBody(receiver.url());
        sendWorkOrder(body, "Authorization", "Bearer tok-9");

        agent.awaitCallbacks(1, Duration.ofSeconds(5));
        CallbackReceiver.Received received = receiver.received().get(0);
        assertThat(received.header("X-Echo")).isEqualTo("tok-9");
        assertThat(received.body()).isEqualTo("{\"len\":" + body.getBytes(StandardCharsets.UTF_8).length + "}");
    }

    @Test
    void severalFakeAgentsRunAtOnceAndRecordSeparately() throws Exception {
        try (FakeAgent other = FakeAgent.start()) {
            other.replyWith(FakeAgentReply.status(429));

            HttpResponse<String> toOther = http.send(HttpRequest.newBuilder(URI.create(other.webhookUrl()))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"taskId\":\"x\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            sendWorkOrder("{\"taskId\":\"y\"}");

            assertThat(other.webhookUrl()).isNotEqualTo(agent.webhookUrl());
            assertThat(toOther.statusCode()).isEqualTo(429);
            assertThat(other.workOrders()).extracting(RecordedWorkOrder::bodyText).containsExactly("{\"taskId\":\"x\"}");
            assertThat(agent.workOrders()).extracting(RecordedWorkOrder::bodyText).containsExactly("{\"taskId\":\"y\"}");
        }
    }
}
