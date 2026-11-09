package com.hireai.migration;

import com.hireai.support.harness.HarnessContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression for V29 (#73): a database holding tasks in the old AWAITING_CAPACITY status comes out
 * of the migration with those tasks in AWAITING_AGENT, other statuses untouched, and the status
 * CHECK accepting only the new name. Runs Flyway directly on a fresh database in the shared
 * harness Postgres: migrate to V28, seed old rows, migrate to the latest version.
 */
class AwaitingAgentMigrationIntegrationTest {

    @BeforeAll
    static void requireDocker() {
        assumeTrue(HarnessContainers.dockerAvailable(), "Docker is not available");
    }

    @Test
    void migrationMovesAwaitingCapacityTasksToAwaitingAgent() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(freshDatabase());
        flyway(jdbc, "28").migrate();

        UUID client = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", client, client + "@test.local");
        UUID waiting = insertTask(jdbc, client, "AWAITING_CAPACITY");
        UUID queued = insertTask(jdbc, client, "QUEUED");

        flyway(jdbc, null).migrate();

        assertThat(status(jdbc, waiting)).isEqualTo("AWAITING_AGENT");
        assertThat(status(jdbc, queued)).isEqualTo("QUEUED");
        assertThatThrownBy(() -> insertTask(jdbc, client, "AWAITING_CAPACITY"))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID fresh = insertTask(jdbc, client, "AWAITING_AGENT");
        assertThat(status(jdbc, fresh)).isEqualTo("AWAITING_AGENT");
    }

    private static Flyway flyway(JdbcTemplate jdbc, String target) {
        var config = Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static PGSimpleDataSource freshDatabase() throws Exception {
        PostgreSQLContainer<?> pg = HarnessContainers.postgres();
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + name));
        dataSource.setUser(pg.getUsername());
        dataSource.setPassword(pg.getPassword());
        return dataSource;
    }

    private static UUID insertTask(JdbcTemplate jdbc, UUID client, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status) "
                        + "VALUES (?, ?, 'Task', 'desc', 10.00, ?::jsonb, ?)",
                id, client, "{\"format\":\"JSON\"}", status);
        return id;
    }

    private static String status(JdbcTemplate jdbc, UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }
}
