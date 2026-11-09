package com.hireai.support.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * An embedded, scriptable stand-in for a third-party Agent's webhook, bound to
 * {@code localhost} on a free port. It records every WorkOrder (headers and exact body bytes) in
 * arrival order and answers each one with a {@link FakeAgentReply}: one-off replies queued with
 * {@link #replyOnceWith} are used first, in order, then the default set by {@link #replyWith}
 * (initially a plain 200). Requests are handled concurrently, so a hanging reply never blocks
 * another WorkOrder. Start as many as a test needs; each is independent.
 */
public final class FakeAgent implements AutoCloseable {

    private static final String PATH = "/work-orders";

    private final HttpServer server;
    private final ExecutorService handlerPool = Executors.newCachedThreadPool(daemon("fake-agent-http"));
    private final ScheduledExecutorService callbackScheduler =
            Executors.newSingleThreadScheduledExecutor(daemon("fake-agent-callback"));
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final List<RecordedWorkOrder> workOrders = new CopyOnWriteArrayList<>();
    private final List<CallbackOutcome> callbacks = new CopyOnWriteArrayList<>();
    private final Queue<FakeAgentReply> oneOffReplies = new ConcurrentLinkedQueue<>();
    private final AtomicReference<FakeAgentReply> defaultReply = new AtomicReference<>(FakeAgentReply.ok());
    private final AtomicReference<CountDownLatch> hangRelease = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile boolean closed;

    private FakeAgent(HttpServer server) {
        this.server = server;
        server.createContext(PATH, this::handle);
        server.setExecutor(handlerPool);
        server.start();
    }

    public static FakeAgent start() {
        try {
            return new FakeAgent(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not start fake Agent", ex);
        }
    }

    /** The URL to register as this Agent's webhook ({@code http://localhost:<port>/work-orders}). */
    public String webhookUrl() {
        return "http://localhost:" + server.getAddress().getPort() + PATH;
    }

    /** Answer every WorkOrder with {@code reply} once the one-off queue is empty. */
    public FakeAgent replyWith(FakeAgentReply reply) {
        defaultReply.set(reply);
        return this;
    }

    /** Answer the next not-yet-answered WorkOrder with {@code reply}. Calls queue in order. */
    public FakeAgent replyOnceWith(FakeAgentReply reply) {
        oneOffReplies.add(reply);
        return this;
    }

    /** Snapshot of every WorkOrder received so far, in arrival order. */
    public List<RecordedWorkOrder> workOrders() {
        return List.copyOf(workOrders);
    }

    /** Wait until at least {@code count} WorkOrders have arrived; fail the test otherwise. */
    public List<RecordedWorkOrder> awaitWorkOrders(int count, Duration timeout) {
        return awaitSize(this::workOrders, count, timeout, "WorkOrders");
    }

    /** Snapshot of every scripted callback sent so far, in the order they completed. */
    public List<CallbackOutcome> callbacks() {
        return List.copyOf(callbacks);
    }

    /** Wait until at least {@code count} scripted callbacks have completed; fail otherwise. */
    public List<CallbackOutcome> awaitCallbacks(int count, Duration timeout) {
        return awaitSize(this::callbacks, count, timeout, "callbacks");
    }

    /** Let every reply that is currently hanging (or delaying) go out now. */
    public void releaseHangs() {
        hangRelease.getAndSet(new CountDownLatch(1)).countDown();
    }

    @Override
    public void close() {
        closed = true;
        releaseHangs();
        server.stop(0);
        callbackScheduler.shutdownNow();
        handlerPool.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            RecordedWorkOrder order = new RecordedWorkOrder(
                    sequence.getAndIncrement(), Instant.now(), exchange.getRequestHeaders(), body);
            workOrders.add(order);

            FakeAgentReply reply = nextReply();
            CountDownLatch release = hangRelease.get();
            if (reply.hangs()) {
                awaitQuietly(release, null);
            } else if (!reply.delay().isZero()) {
                awaitQuietly(release, reply.delay());
            }

            AgentCallback callback = reply.callback().orElse(null);
            if (callback != null && callback.sentBeforeReply() && !closed) {
                sendCallback(callback, order);
            }

            byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), response.length == 0 ? -1 : response.length);
            if (response.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
            }

            if (callback != null && !callback.sentBeforeReply() && !closed) {
                callbackScheduler.schedule(() -> sendCallback(callback, order),
                        callback.delay().toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private FakeAgentReply nextReply() {
        FakeAgentReply oneOff = oneOffReplies.poll();
        return oneOff != null ? oneOff : defaultReply.get();
    }

    private void sendCallback(AgentCallback callback, RecordedWorkOrder order) {
        CallbackRequest request = null;
        try {
            request = callback.buildFor(order);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request.bodyBytes()));
            request.headers().forEach(builder::header);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            callbacks.add(new CallbackOutcome(request, response.statusCode(), response.body(),
                    callback.sentBeforeReply(), null));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            callbacks.add(new CallbackOutcome(request, -1, null, callback.sentBeforeReply(), ex.toString()));
        }
    }

    private static void awaitQuietly(CountDownLatch latch, Duration timeout) {
        try {
            if (timeout == null) {
                latch.await();
            } else {
                latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> List<T> awaitSize(Supplier<List<T>> snapshot, int count, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<T> current = snapshot.get();
            if (current.size() >= count) {
                return current;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Expected at least " + count + " " + what + " within " + timeout
                        + " but got " + current.size() + ": " + current);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + what, ex);
            }
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
