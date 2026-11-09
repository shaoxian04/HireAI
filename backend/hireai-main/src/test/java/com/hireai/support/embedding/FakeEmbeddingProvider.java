package com.hireai.support.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A local stand-in for the OpenAI embeddings endpoint ({@code POST <baseUrl>/embeddings}), so no
 * test ever calls the real provider. It records every request and answers with {@link #SAME_FOR_EVERY_TEXT}
 * ({@link #vectorFor} overrides it, e.g. with {@link #deterministicVector} or {@link #direction}), or
 * fails while {@link #down()} (503) or hangs while {@link #hanging()}.
 *
 * <p>The default answer is one vector for every text, so every task/Agent pair has cosine similarity
 * 1: all Agents pass the relevance floor (#85) and tie on relevance, and tests that don't script
 * the provider rank exactly as they would without embeddings.
 *
 * <p>{@link #shared()} is the one instance every Spring test context's embedding client points at
 * (see {@link TestEmbeddingConfig}); tests that script it call {@link #reset()} first.
 */
public final class FakeEmbeddingProvider implements AutoCloseable {

    public static final int DIMENSIONS = 1536;

    /** The default answer: one fixed vector for every text (cosine 1 between any two texts). */
    public static final float[] SAME_FOR_EVERY_TEXT = constant(0.025f);

    private static FakeEmbeddingProvider shared;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fake-embedding-http");
        t.setDaemon(true);
        return t;
    });
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Function<String, float[]> vectors = text -> SAME_FOR_EVERY_TEXT;
    private volatile Mode mode = Mode.UP;
    private volatile CountDownLatch hangRelease = new CountDownLatch(1);

    private enum Mode { UP, DOWN, HANGING }

    /** One received request: the input text, the requested model and the bearer it carried. */
    public record Recorded(String input, String model, String authorization) {
    }

    private FakeEmbeddingProvider(HttpServer server) {
        this.server = server;
        server.createContext("/embeddings", this::handle);
        server.setExecutor(pool);
        server.start();
    }

    public static FakeEmbeddingProvider start() {
        try {
            return new FakeEmbeddingProvider(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not start the fake embedding provider", ex);
        }
    }

    /** The JVM-wide instance the test contexts use; started on first call, a daemon until exit. */
    public static synchronized FakeEmbeddingProvider shared() {
        if (shared == null) {
            shared = start();
        }
        return shared;
    }

    /** The base URL to configure, e.g. {@code http://localhost:1234} (the client appends /embeddings). */
    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Back to up, default vectors and no recorded requests. */
    public FakeEmbeddingProvider reset() {
        releaseHangs();
        mode = Mode.UP;
        vectors = text -> SAME_FOR_EVERY_TEXT;
        requests.clear();
        return this;
    }

    public FakeEmbeddingProvider down() {
        mode = Mode.DOWN;
        return this;
    }

    public FakeEmbeddingProvider up() {
        releaseHangs();
        mode = Mode.UP;
        return this;
    }

    public FakeEmbeddingProvider hanging() {
        mode = Mode.HANGING;
        return this;
    }

    public FakeEmbeddingProvider vectorFor(Function<String, float[]> vectors) {
        this.vectors = vectors;
        return this;
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    /** A vector whose components are all {@code value} (handy to recognise a stored vector). */
    public static float[] constant(float value) {
        float[] v = new float[DIMENSIONS];
        java.util.Arrays.fill(v, value);
        return v;
    }

    /**
     * A unit vector at {@code angleRadians} in the plane of the first two axes: the cosine between
     * {@code direction(a)} and {@code direction(b)} is exactly {@code cos(a - b)}, so a test can pick the
     * similarity of each task/Agent pair.
     */
    public static float[] direction(double angleRadians) {
        float[] v = new float[DIMENSIONS];
        v[0] = (float) Math.cos(angleRadians);
        v[1] = (float) Math.sin(angleRadians);
        return v;
    }

    /** A unit vector whose cosine similarity with {@code direction(0)} is {@code cosine}. */
    public static float[] atCosineToAxis(double cosine) {
        return direction(Math.acos(cosine));
    }

    /** A fixed pseudo-random vector seeded by the text, so equal texts get equal vectors. */
    public static float[] deterministicVector(String text) {
        Random random = new Random(text.hashCode());
        float[] v = new float[DIMENSIONS];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (random.nextGaussian() / Math.sqrt(DIMENSIONS));
        }
        return v;
    }

    private void releaseHangs() {
        hangRelease.countDown();
        hangRelease = new CountDownLatch(1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            String input = body.path("input").asText();
            String model = body.path("model").asText();
            requests.add(new Recorded(input, model, exchange.getRequestHeaders().getFirst("Authorization")));
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
            response.put("object", "list");
            response.put("model", model);
            ArrayNode data = response.putArray("data");
            ObjectNode item = data.addObject();
            item.put("object", "embedding");
            item.put("index", 0);
            ArrayNode embedding = item.putArray("embedding");
            for (float f : vectors.apply(input)) {
                embedding.add(f);
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
