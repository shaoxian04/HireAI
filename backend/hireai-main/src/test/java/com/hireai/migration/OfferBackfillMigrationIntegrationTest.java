package com.hireai.migration;

import com.hireai.support.harness.HarnessContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression for V39 (#88, spec D39): every agent version that existed before Offers comes out of
 * the migration with the equivalent Offer, and Tasks already submitted keep their output_spec
 * untouched and no Contract. Published Template versions cannot be edited or deleted.
 */
class OfferBackfillMigrationIntegrationTest {

    private static final String SCHEMA = "{\"type\":\"object\"}";

    @BeforeAll
    static void requireDocker() {
        assumeTrue(HarnessContainers.dockerAvailable(), "Docker is not available");
    }

    @Test
    void existingVersionsGetTheEquivalentOfferAndInFlightTasksKeepTheirOutputSpec() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(freshDatabase());
        flyway(jdbc, "38").migrate();

        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", owner, owner + "@test.local");
        UUID text = insertVersion(jdbc, owner, "{\"format\":\"TEXT\",\"acceptanceCriteria\":\"bullets\"}");
        UUID file = insertVersion(jdbc, owner, "{\"format\":\"FILE\"}");
        UUID json = insertVersion(jdbc, owner,
                "{\"format\":\"JSON\",\"schema\":\"" + SCHEMA.replace("\"", "\\\"") + "\"}");
        UUID schemaless = insertVersion(jdbc, owner, "{\"format\":\"JSON\",\"schema\":\"  \"}");
        String inFlightSpec = "{\"format\":\"JSON\",\"schema\":\"{}\",\"acceptanceCriteria\":\"old rules\"}";
        UUID task = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status, category) "
                + "VALUES (?, ?, 'In flight', 'Running', 10.00, ?::jsonb, 'EXECUTING', 'other')", task, owner, inFlightSpec);

        flyway(jdbc, null).migrate();

        assertThat(offer(jdbc, text)).isEqualTo("{\"terms\": [{\"params\": {}, \"version\": 1, \"template\": \"text_basic\"}]}");
        assertThat(offer(jdbc, file)).isEqualTo("{\"terms\": [{\"params\": {}, \"version\": 1, \"template\": \"file_basic\"}]}");
        assertThat(offer(jdbc, json)).isEqualTo("{\"terms\": [{\"params\": {\"schema\": {\"oneOf\": [\""
                + SCHEMA.replace("\"", "\\\"") + "\"]}}, \"version\": 1, \"template\": \"custom_json\"}]}");
        assertThat(offer(jdbc, schemaless))
                .isEqualTo("{\"terms\": [{\"params\": {}, \"version\": 1, \"template\": \"custom_json\"}]}");

        assertThat(jdbc.queryForObject("SELECT output_spec = ?::jsonb FROM tasks WHERE id = ?", Boolean.class,
                inFlightSpec, task)).isTrue();
        assertThat(jdbc.queryForObject("SELECT contract FROM tasks WHERE id = ?", String.class, task)).isNull();

        assertThat(jdbc.queryForList("SELECT template_key || '@' || version FROM contract_templates "
                + "WHERE status = 'ACTIVE' ORDER BY 1", String.class))
                .containsExactly("ap_invoice_extraction@1", "custom_json@1", "file_basic@1", "image_generation@1", "text_basic@1");
    }

    @Test
    void aPublishedTemplateVersionCannotBeEditedOrDeletedButCanBeRetired() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(freshDatabase());
        flyway(jdbc, null).migrate();

        assertThatThrownBy(() -> jdbc.update("UPDATE contract_templates SET definition = '{}'::jsonb "
                + "WHERE template_key = 'text_basic'")).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM contract_templates WHERE template_key = 'text_basic'"))
                .hasMessageContaining("never deleted");

        jdbc.update("UPDATE contract_templates SET status = 'RETIRED' WHERE template_key = 'text_basic'");
        assertThat(jdbc.queryForObject("SELECT status FROM contract_templates WHERE template_key = 'text_basic'",
                String.class)).isEqualTo("RETIRED");
    }

    private static String offer(JdbcTemplate jdbc, UUID versionId) {
        return jdbc.queryForObject("SELECT offer::text FROM agent_versions WHERE id = ?", String.class, versionId);
    }

    private static UUID insertVersion(JdbcTemplate jdbc, UUID owner, String outputSpec) {
        UUID agent = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status) VALUES (?, ?, 'Old Agent', 'ACTIVE')", agent, owner);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, "
                + "'https://agent.example.com/run', 60, ?)", version, agent, outputSpec, new String[]{"other"},
                new BigDecimal("5.00"));
        return version;
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
