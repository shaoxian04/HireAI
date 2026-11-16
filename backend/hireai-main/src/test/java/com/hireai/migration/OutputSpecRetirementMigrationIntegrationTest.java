package com.hireai.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.acceptance.AcceptanceJsonMapper;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.support.harness.HarnessContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
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
 * Regression for V55 (#110, spec D39): tasks.output_spec is retired. Every Task submitted before
 * Contracts existed comes out with the Contract its output spec maps onto (the SQL mapping equals
 * {@link OutputSpec#toContract()}, the rules those Tasks were already judged by), its prose criteria
 * move to {@code acceptance_criteria}, Tasks that already had a Contract keep it, the historical
 * output_spec stays readable, and from then on a Task cannot be stored without a Contract.
 */
class OutputSpecRetirementMigrationIntegrationTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"total\"]}";
    private final AcceptanceJsonMapper mapper = new AcceptanceJsonMapper(new ObjectMapper());

    @BeforeAll
    static void requireDocker() {
        assumeTrue(HarnessContainers.dockerAvailable(), "Docker is not available");
    }

    @Test
    void tasksFromBeforeContractsGetTheirOutputSpecsContractAndKeepTheirHistory() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(freshDatabase());
        flyway(jdbc, "53").migrate();

        UUID client = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", client, client + "@test.local");
        String textSpec = "{\"format\":\"TEXT\",\"acceptanceCriteria\":\"three bullet points\"}";
        String fileSpec = "{\"format\":\"FILE\"}";
        String jsonSpec = "{\"format\":\"JSON\",\"schema\":" + quoted(SCHEMA) + ",\"acceptanceCriteria\":\"totals\"}";
        String blankJsonSpec = "{\"format\":\"JSON\",\"schema\":\"  \"}";
        UUID text = legacyTask(jdbc, client, textSpec, "EXECUTING");
        UUID file = legacyTask(jdbc, client, fileSpec, "PENDING_REVIEW");
        UUID json = legacyTask(jdbc, client, jsonSpec, "RESULT_RECEIVED");
        UUID blankJson = legacyTask(jdbc, client, blankJsonSpec, "RESOLVED");
        UUID modern = UUID.randomUUID();
        String modernContract = "{\"template\":\"text_basic\",\"version\":1,\"params\":{\"max_length\":800}}";
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, contract, status, "
                + "category) VALUES (?, ?, 'Modern', 'd', 10.00, '{\"format\":\"TEXT\",\"acceptanceCriteria\":\"warm\"}'"
                + "::jsonb, ?::jsonb, 'SUBMITTED', 'other')", modern, client, modernContract);

        flyway(jdbc, null).migrate();

        // The SQL mapping is the Java one: in-flight Tasks keep exactly the rules they were judged by.
        assertContract(jdbc, text, new OutputSpec(OutputFormat.TEXT, null, "three bullet points"));
        assertContract(jdbc, file, new OutputSpec(OutputFormat.FILE, null, null));
        assertContract(jdbc, json, new OutputSpec(OutputFormat.JSON, SCHEMA, "totals"));
        assertContract(jdbc, blankJson, new OutputSpec(OutputFormat.JSON, "  ", null));
        assertThat(jdbc.queryForObject("SELECT contract = ?::jsonb FROM tasks WHERE id = ?", Boolean.class,
                modernContract, modern)).isTrue();

        assertThat(criteria(jdbc, text)).isEqualTo("three bullet points");
        assertThat(criteria(jdbc, json)).isEqualTo("totals");
        assertThat(criteria(jdbc, file)).isNull();
        assertThat(criteria(jdbc, modern)).isEqualTo("warm");

        // History stays readable, untouched.
        assertThat(jdbc.queryForObject("SELECT output_spec = ?::jsonb FROM tasks WHERE id = ?", Boolean.class,
                jsonSpec, json)).isTrue();

        // From now on a Task has a Contract and needs no output spec.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, "
                + "output_spec, status, category) VALUES (?, ?, 't', 'd', 1.00, ?::jsonb, 'SUBMITTED', 'other')",
                UUID.randomUUID(), client, textSpec)).hasMessageContaining("contract");
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, contract, status, category) "
                + "VALUES (?, ?, 't', 'd', 1.00, ?::jsonb, 'SUBMITTED', 'other')", UUID.randomUUID(), client,
                modernContract);

        // The backfilled Contract is frozen like any other.
        assertThatThrownBy(() -> jdbc.update("UPDATE tasks SET contract = ?::jsonb WHERE id = ?",
                modernContract, text)).hasMessageContaining("frozen");
    }

    private void assertContract(JdbcTemplate jdbc, UUID taskId, OutputSpec spec) {
        assertThat(jdbc.queryForObject("SELECT contract = ?::jsonb FROM tasks WHERE id = ?", Boolean.class,
                mapper.toJson(spec.toContract()), taskId)).as("contract of %s", spec).isTrue();
    }

    private static String criteria(JdbcTemplate jdbc, UUID taskId) {
        return jdbc.queryForObject("SELECT acceptance_criteria FROM tasks WHERE id = ?", String.class, taskId);
    }

    private static UUID legacyTask(JdbcTemplate jdbc, UUID client, String outputSpec, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status, category) "
                + "VALUES (?, ?, 'Before contracts', 'd', 10.00, ?::jsonb, ?, 'other')", id, client, outputSpec, status);
        return id;
    }

    private static String quoted(String json) {
        return "\"" + json.replace("\"", "\\\"") + "\"";
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
}
