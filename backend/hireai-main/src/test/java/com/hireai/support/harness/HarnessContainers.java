package com.hireai.support.harness;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One Postgres and one RabbitMQ container for the whole test JVM, started lazily on first use
 * and reaped by Testcontainers' Ryuk when the JVM exits. Lives apart from
 * {@link WholeAppIntegrationTest} so that evaluating the Docker condition never starts a
 * container (and never fails) on a machine without Docker.
 */
public final class HarnessContainers {

    private static PostgreSQLContainer<?> postgres;
    private static RabbitMQContainer rabbit;

    private HarnessContainers() {
    }

    /** Condition for {@code @EnabledIf}: false (skip) when no Docker daemon is reachable. */
    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
            postgres = container;
        }
        return postgres;
    }

    public static synchronized RabbitMQContainer rabbit() {
        if (rabbit == null) {
            RabbitMQContainer container = new RabbitMQContainer("rabbitmq:3.13-management-alpine");
            container.start();
            rabbit = container;
        }
        return rabbit;
    }

    /**
     * A fresh, empty database in the shared Postgres. Each Spring context gets its own, so cached
     * contexts never see each other's rows. Returns its JDBC URL.
     */
    static String newDatabase(String name) {
        PostgreSQLContainer<?> pg = postgres();
        try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not create database " + name, ex);
        }
        return pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + name);
    }

    /**
     * A fresh RabbitMQ virtual host. Each Spring context gets its own, so a cached context's
     * listeners can never consume a message published by another context.
     */
    static String newVirtualHost(String name) {
        RabbitMQContainer mq = rabbit();
        try {
            exec(mq, "rabbitmqctl", "add_vhost", name);
            exec(mq, "rabbitmqctl", "set_permissions", "-p", name, mq.getAdminUsername(), ".*", ".*", ".*");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        return name;
    }

    private static void exec(RabbitMQContainer mq, String... command) throws IOException, InterruptedException {
        Container.ExecResult result = mq.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + result.getStderr());
        }
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException("No free port for the platform under test", ex);
        }
    }
}
