package com.hireai.support.delivery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An Agent's file host for tests: an HTTPS server on {@code localhost} (certificate from
 * {@link TestTls}) serving whatever each path is set to. A test can {@link #serve} a file,
 * {@link #redirect} or {@link #fail} a path, then {@link #serve} different bytes or {@link #remove}
 * it after delivery, to prove the platform judges its snapshot rather than the live link.
 */
public final class FakeFileHost implements AutoCloseable {

    private record Reply(int status, byte[] body, String contentType, String location) {
    }

    private final HttpsServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private FakeFileHost(HttpsServer server) {
        this.server = server;
        server.createContext("/", this::handle);
        server.setExecutor(pool);
        server.start();
    }

    public static FakeFileHost start() {
        try {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(TestTls.serverKeyStore())) {
                keys.load(in, TestTls.PASSWORD.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, TestTls.PASSWORD.toCharArray());
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(kmf.getKeyManagers(), null, null);
            HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls));
            return new FakeFileHost(server);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the fake file host", e);
        }
    }

    /** {@code https://localhost:<port><path>}. */
    public String url(String path) {
        return "https://localhost:" + server.getAddress().getPort() + path;
    }

    public FakeFileHost serve(String path, byte[] body, String contentType) {
        replies.put(path, new Reply(200, body, contentType, null));
        return this;
    }

    public FakeFileHost redirect(String path, String location) {
        replies.put(path, new Reply(302, new byte[0], null, location));
        return this;
    }

    public FakeFileHost fail(String path, int status) {
        replies.put(path, new Reply(status, new byte[0], null, null));
        return this;
    }

    public FakeFileHost remove(String path) {
        replies.remove(path);
        return this;
    }

    /** Every path requested so far, in order. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            requests.add(path);
            Reply reply = replies.getOrDefault(path, new Reply(404, new byte[0], null, null));
            if (reply.contentType() != null) {
                exchange.getResponseHeaders().add("Content-Type", reply.contentType());
            }
            if (reply.location() != null) {
                exchange.getResponseHeaders().add("Location", reply.location());
            }
            exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
            if (reply.body().length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(reply.body());
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}
