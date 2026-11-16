package com.hireai.support.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The scripted requirement extractor: a local stand-in for the OpenAI chat completions endpoint
 * ({@code POST <baseUrl>/chat/completions}), so no test ever calls a real LLM. Same style as
 * {@code FakeEmbeddingProvider}: the real {@code OpenAiRequirementExtractor} talks to it (see
 * {@link TestExtractionConfig}), so the request shape, the structured-output schema and the timeout
 * are exercised too.
 *
 * <p>It records every request and answers with the scripted {@code content} JSON (by default
 * {@link #NOTHING_EXTRACTED}: no requirements, so every untouched test keeps the Template's
 * defaults). {@link #down()} answers 503, {@link #hanging()} never answers until released,
 * {@link #refusing()} returns a model refusal. {@link #shared()} is the one instance every Spring
 * test context points at; tests that script it call {@link #reset()} before AND after each test
 * (a leftover script would make other classes' routed submits extract terms no seeded Offer covers).
 */
public final class FakeExtractionProvider implements AutoCloseable {

    /** The default reply content: the model found no requirement. */
    public static final String NOTHING_EXTRACTED = "{\"requirements\":[]}";

    private static FakeExtractionProvider shared;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fake-extraction-http");
        t.setDaemon(true);
        return t;
    });
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Recorded, String> content = r -> NOTHING_EXTRACTED;
    private volatile Mode mode = Mode.UP;
    private volatile CountDownLatch hangRelease = new CountDownLatch(1);

    private enum Mode { UP, DOWN, HANGING, REFUSING }

    /**
     * One received request: the model, the system prompt, the user message (the task text), the
     * structured-output schema it asked for, and the bearer it carried.
     */
    public record Recorded(String model, String systemPrompt, String userMessage, JsonNode schema,
                           String authorization) {
    }

    private FakeExtractionProvider(HttpServer server) {
        this.server = server;
        server.createContext("/chat/completions", this::handle);
        server.setExecutor(pool);
        server.start();
    }

    public static FakeExtractionProvider start() {
        try {
            return new FakeExtractionProvider(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not start the fake extraction provider", ex);
        }
    }

    /** The JVM-wide instance the test contexts use; started on first call, a daemon until exit. */
    public static synchronized FakeExtractionProvider shared() {
        if (shared == null) {
            shared = start();
        }
        return shared;
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Back to up, nothing extracted and no recorded requests. */
    public FakeExtractionProvider reset() {
        releaseHangs();
        mode = Mode.UP;
        content = r -> NOTHING_EXTRACTED;
        requests.clear();
        return this;
    }

    /** Every request answers with this reply content (the JSON the model would return). */
    public FakeExtractionProvider answering(String contentJson) {
        return answering(r -> contentJson);
    }

    public FakeExtractionProvider answering(Function<Recorded, String> content) {
        this.content = content;
        return this;
    }

    public FakeExtractionProvider down() {
        mode = Mode.DOWN;
        return this;
    }

    public FakeExtractionProvider hanging() {
        mode = Mode.HANGING;
        return this;
    }

    public FakeExtractionProvider refusing() {
        mode = Mode.REFUSING;
        return this;
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    private void releaseHangs() {
        hangRelease.countDown();
        hangRelease = new CountDownLatch(1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            JsonNode messages = body.path("messages");
            Recorded recorded = new Recorded(body.path("model").asText(), messages.path(0).path("content").asText(),
                    messages.path(1).path("content").asText(),
                    body.path("response_format").path("json_schema").path("schema"),
                    exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(recorded);
            Mode current = mode;
            if (current == Mode.HANGING) {
                CountDownLatch latch = hangRelease;
                latch.await(30, TimeUnit.SECONDS);
                current = Mode.DOWN;
            }
            if (current == Mode.DOWN) {
                send(exchange, 503, "{\"error\":{\"message\":\"fake provider down\"}}");
                return;
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("object", "chat.completion");
            response.put("model", recorded.model());
            ObjectNode message = response.putArray("choices").addObject().put("index", 0).putObject("message");
            message.put("role", "assistant");
            if (current == Mode.REFUSING) {
                message.putNull("content");
                message.put("refusal", "I can't help with that.");
            } else {
                message.put("content", content.apply(recorded));
            }
            send(exchange, 200, mapper.writeValueAsString(response));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        releaseHangs();
        server.stop(0);
        pool.shutdownNow();
    }
}
