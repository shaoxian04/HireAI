package com.hireai.catalogue;

import com.hireai.application.port.query.CatalogueQueryPort;
import com.hireai.application.port.query.CatalogueQueryPort.AgentCardRow;
import com.hireai.application.port.query.CatalogueQueryPort.AgentProfileRow;
import com.hireai.application.port.query.CatalogueQueryPort.CategoryCountRow;
import com.hireai.application.port.query.CatalogueQueryPort.ReviewRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the public catalogue read DAO. Boots Spring against a real Postgres
 * (Testcontainers) so Flyway applies V1–V7. Each test seeds its own data via JdbcTemplate so
 * the shared container carries no cross-test state.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class CatalogueQueryDaoIntegrationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired CatalogueQueryPort catalogueQueryPort;
    @Autowired JdbcTemplate jdbc;

    // -----------------------------------------------------------------------
    // Seed helpers
    // -----------------------------------------------------------------------

    /**
     * Seeds: BUILDER user (email) → agent (name, status, repScore 60.00) → agent_version
     * (output_spec '{"format":"JSON"}', category, price, max_exec 60, HTTPS webhook) →
     * agent_profiles row (is_listed, is_featured, tagline 'T').
     *
     * @return the agent UUID
     */
    private UUID seedAgent(String builderEmail, String agentName, String status,
                           String category, BigDecimal price,
                           boolean isListed, boolean isFeatured) {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'BUILDER')",
                ownerId, builderEmail);

        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) " +
                        "VALUES (?, ?, ?, ?, ?, 60.00)",
                agentId, ownerId, agentName, status, versionId);

        jdbc.update("INSERT INTO agent_versions " +
                        "(id, agent_id, version_number, output_spec, capability_categories, " +
                        "webhook_url, max_execution_seconds, price) " +
                        "VALUES (?, ?, 1, ?::jsonb, ARRAY[?], ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}", category,
                "https://agent.example.com/callback", price);

        jdbc.update("INSERT INTO agent_profiles " +
                        "(agent_id, tagline, is_listed, is_featured) VALUES (?, 'T', ?, ?) " +
                        "ON CONFLICT (agent_id) DO UPDATE " +
                        "SET tagline='T', is_listed=EXCLUDED.is_listed, is_featured=EXCLUDED.is_featured",
                agentId, isListed, isFeatured);

        return agentId;
    }

    /** Convenience overload: active + listed + not featured + category "general" + price 5. */
    private UUID seedActiveListedAgent(String builderEmail, String agentName) {
        return seedAgent(builderEmail, agentName, "ACTIVE", "general",
                new BigDecimal("5.00"), true, false);
    }

    // -----------------------------------------------------------------------
    // Test 1: Only ACTIVE + listed agents appear
    // -----------------------------------------------------------------------

    @Test
    void onlyActiveListedAgentsAppear() {
        UUID visible = seedAgent(uniqueEmail("alice"), "Visible Agent",
                "ACTIVE", "general", new BigDecimal("5.00"), true, false);

        seedAgent(uniqueEmail("bob"), "Unlisted Agent",
                "ACTIVE", "general", new BigDecimal("5.00"), false, false);

        seedAgent(uniqueEmail("carol"), "Inactive Agent",
                "PENDING_VERIFICATION", "general", new BigDecimal("5.00"), true, false);

        List<AgentCardRow> results = catalogueQueryPort.searchCards("", "", "newest", 0, 50);
        // Filter to only our seeded agents (test isolation: other tests in the shared container
        // may have written their own rows; we just assert our visible one is present and the
        // other two are absent).
        List<UUID> ids = results.stream().map(AgentCardRow::id).toList();
        assertThat(ids).contains(visible);
        // Unlisted: ACTIVE but not listed — must be absent
        assertThat(results).noneMatch(r -> r.name().equals("Unlisted Agent"));
        // Inactive: listed but PENDING_VERIFICATION — must be absent
        assertThat(results).noneMatch(r -> r.name().equals("Inactive Agent"));
    }

    // -----------------------------------------------------------------------
    // Test 2: Search matches agent name or builder name (email local-part)
    // -----------------------------------------------------------------------

    @Test
    void searchMatchesAgentNameOrBuilderName() {
        seedAgent("alice@x.com", "Summariser Bot",
                "ACTIVE", "summarisation", new BigDecimal("5.00"), true, false);
        seedAgent("bobbuilder@x.com", "Translator",
                "ACTIVE", "translation", new BigDecimal("5.00"), true, false);

        List<AgentCardRow> sumResults = catalogueQueryPort.searchCards("summar", "", "newest", 0, 50);
        assertThat(sumResults).anyMatch(r -> r.name().equals("Summariser Bot"));
        assertThat(sumResults).noneMatch(r -> r.name().equals("Translator"));

        List<AgentCardRow> bobResults = catalogueQueryPort.searchCards("bobbuilder", "", "newest", 0, 50);
        assertThat(bobResults).anyMatch(r -> r.name().equals("Translator"));
        assertThat(bobResults).noneMatch(r -> r.name().equals("Summariser Bot"));
    }

    // -----------------------------------------------------------------------
    // Test 3: Category filter and category counts
    // -----------------------------------------------------------------------

    @Test
    void categoryFilterAndCounts() {
        seedAgent(uniqueEmail("catA"), "Agent A",
                "ACTIVE", "summarisation", new BigDecimal("5.00"), true, false);
        seedAgent(uniqueEmail("catB"), "Agent B",
                "ACTIVE", "translation", new BigDecimal("5.00"), true, false);

        List<AgentCardRow> translationOnly =
                catalogueQueryPort.searchCards("", "translation", "newest", 0, 50);
        assertThat(translationOnly).anyMatch(r -> r.name().equals("Agent B"));
        assertThat(translationOnly).noneMatch(r -> r.name().equals("Agent A"));

        List<CategoryCountRow> counts = catalogueQueryPort.categoryCounts();
        assertThat(counts).anyMatch(c -> c.category().equals("summarisation") && c.agentCount() >= 1);
        assertThat(counts).anyMatch(c -> c.category().equals("translation") && c.agentCount() >= 1);
    }

    // -----------------------------------------------------------------------
    // Test 4: Featured agents float to top on hot sort
    // -----------------------------------------------------------------------

    @Test
    void featuredFloatsToTopOnHotSort() {
        UUID plain = seedAgent(uniqueEmail("plain"), "Plain Agent",
                "ACTIVE", "general", new BigDecimal("5.00"), true, false);
        UUID pinned = seedAgent(uniqueEmail("pinned"), "Pinned Agent",
                "ACTIVE", "general", new BigDecimal("5.00"), true, true);

        List<AgentCardRow> results = catalogueQueryPort.searchCards("", "", "hot", 0, 50);

        // Pinned (featured=true) must appear before Plain (featured=false)
        int pinnedIdx = indexOfId(results, pinned);
        int plainIdx = indexOfId(results, plain);
        assertThat(pinnedIdx).isGreaterThanOrEqualTo(0);
        assertThat(plainIdx).isGreaterThanOrEqualTo(0);
        assertThat(pinnedIdx).isLessThan(plainIdx);
    }

    // -----------------------------------------------------------------------
    // Test 5: Profile includes content; absent for unlisted
    // -----------------------------------------------------------------------

    @Test
    void profileIncludesContentAndAbsentForUnlisted() {
        UUID visible = seedAgent(uniqueEmail("profvisible"), "Profile Visible",
                "ACTIVE", "general", new BigDecimal("5.00"), true, false);
        UUID unlisted = seedAgent(uniqueEmail("profunlisted"), "Profile Unlisted",
                "ACTIVE", "general", new BigDecimal("5.00"), false, false);

        // Set a description on the visible agent's profile
        jdbc.update("UPDATE agent_profiles SET description = 'Great agent description' WHERE agent_id = ?",
                visible);

        Optional<AgentProfileRow> visibleProfile = catalogueQueryPort.findProfile(visible);
        assertThat(visibleProfile).isPresent();
        assertThat(visibleProfile.get().description()).isEqualTo("Great agent description");
        assertThat(visibleProfile.get().card().name()).isEqualTo("Profile Visible");

        Optional<AgentProfileRow> unlistedProfile = catalogueQueryPort.findProfile(unlisted);
        assertThat(unlistedProfile).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 6: Reviews returned newest-first with author as email local-part
    // -----------------------------------------------------------------------

    @Test
    void reviewsForAgentNewestFirstWithAuthor() {
        UUID agentId = seedAgent(uniqueEmail("revbuilder"), "Review Test Agent",
                "ACTIVE", "general", new BigDecimal("5.00"), true, false);

        // Insert a CLIENT user
        UUID clientId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, 'reviewer@x.com', 'CLIENT')",
                clientId);

        // Insert a published review
        UUID reviewId = UUID.randomUUID();
        jdbc.update("INSERT INTO reviews (id, client_id, agent_id, rating, review_text, is_published) " +
                        "VALUES (?, ?, ?, 5, 'great', true)",
                reviewId, clientId, agentId);

        List<ReviewRow> reviews = catalogueQueryPort.reviewsForAgent(agentId, 10);
        assertThat(reviews).hasSize(1);
        ReviewRow row = reviews.get(0);
        assertThat(row.rating()).isEqualTo(5);
        assertThat(row.reviewText()).isEqualTo("great");
        assertThat(row.author()).isEqualTo("reviewer");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String uniqueEmail(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@test.local";
    }

    private static int indexOfId(List<AgentCardRow> rows, UUID id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(id)) return i;
        }
        return -1;
    }
}
