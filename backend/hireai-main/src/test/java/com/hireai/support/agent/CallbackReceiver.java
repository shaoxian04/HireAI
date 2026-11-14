package com.hireai.support.agent;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A tiny localhost HTTP receiver that records each POST and answers with a fixed status. Used
 * where the real platform is not running (the fake Agent's own tests). Whole-app tests point
 * the fake Agent at the real callback endpoint instead.
 */
final class CallbackReceiver implements AutoCloseable {

    record Received(com.sun.net.httpserver.Headers headers, String body) {
        String header(String name) {
            return headers.getFirst(name);
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();

    private CallbackReceiver(HttpServer server, int status) {
        this.server = server;
        server.createContext("/callback", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.add(new Received(exchange.getRequestHeaders(), body));
                exchange.sendResponseHeaders(status, -1);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    static CallbackReceiver start(int status) {
        try {
            return new CallbackReceiver(HttpServer.create(new InetSocketAddress("localhost", 0), 0), status);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/callback";
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
