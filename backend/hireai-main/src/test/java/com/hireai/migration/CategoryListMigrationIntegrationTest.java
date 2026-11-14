package com.hireai.migration;

import com.hireai.support.harness.HarnessContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression for V35 (#80): free-text capability tags written before the platform-owned category
 * list come out of the migration as slugs from that list. Known spellings map onto their bucket,
 * duplicates collapse, and anything unrecognised becomes {@code other}. Task categories map the
 * same way, so a rebook of an old task still names a valid slug.
 */
class CategoryListMigrationIntegrationTest {

    @BeforeAll
    static void requireDocker() {
        assumeTrue(HarnessContainers.dockerAvailable(), "Docker is not available");
    }

    @Test
    void migrationMapsFreeTextTagsOntoTheCategoryList() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(freshDatabase());
        flyway(jdbc, "34").migrate();

        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", owner, owner + "@test.local");
        UUID summariser = insertVersion(jdbc, owner, "Summarization", " summarise ", "Invoice Extraction");
        UUID niche = insertVersion(jdbc, owner, "quantum-poetry");
        UUID translator = insertVersion(jdbc, owner, "translation", "general");
        UUID oldTask = insertTask(jdbc, owner, "summarize");
        UUID oddTask = insertTask(jdbc, owner, "underwater-basket-weaving");
        UUID directTask = insertTask(jdbc, owner, null);

        flyway(jdbc, null).migrate();

        assertThat(categories(jdbc, summariser)).containsExactly("summarisation", "document-extraction");
        assertThat(categories(jdbc, niche)).containsExactly("other");
        assertThat(categories(jdbc, translator)).containsExactly("translation", "other");
        assertThat(taskCategory(jdbc, oldTask)).isEqualTo("summarisation");
        assertThat(taskCategory(jdbc, oddTask)).isEqualTo("other");
        assertThat(taskCategory(jdbc, directTask)).isNull();
        assertThat(jdbc.queryForList("SELECT slug FROM categories", String.class))
                .contains("document-extraction", "summarisation", "translation", "other");
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

    private static UUID insertVersion(JdbcTemplate jdbc, UUID owner, String... tags) {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id) "
                + "VALUES (?, ?, 'Old Agent', 'ACTIVE', ?)", agentId, owner, versionId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, 5.00)",
                versionId, agentId, "{\"format\":\"JSON\"}", tags, "https://agent.example.com/hook");
        return versionId;
    }

    private static UUID insertTask(JdbcTemplate jdbc, UUID client, String category) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, category, status) "
                        + "VALUES (?, ?, 'Task', 'desc', 10.00, ?::jsonb, ?, 'RESOLVED')",
                id, client, "{\"format\":\"JSON\"}", category);
        return id;
    }

    private static List<String> categories(JdbcTemplate jdbc, UUID versionId) {
        return jdbc.queryForObject("SELECT capability_categories FROM agent_versions WHERE id = ?",
                (rs, i) -> {
                    Array array = rs.getArray(1);
                    return List.of((String[]) array.getArray());
                }, versionId);
    }

    private static String taskCategory(JdbcTemplate jdbc, UUID taskId) {
        return jdbc.queryForObject("SELECT category FROM tasks WHERE id = ?", String.class, taskId);
    }
}
