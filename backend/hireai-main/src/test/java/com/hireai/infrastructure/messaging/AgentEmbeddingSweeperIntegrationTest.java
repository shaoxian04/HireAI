package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.offering.embedding.AgentEmbeddingAppService;
import com.hireai.support.embedding.FakeEmbeddingProvider;
import com.hireai.support.harness.WholeAppIntegrationTest;
import com.hireai.utility.hash.Sha256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #84: the re-embed sweeper (called directly; scheduling is off under the test profile) fills
 * missing vectors and replaces those whose text hash is stale or whose model differs, and leaves
 * fresh ones alone. A provider outage ends the pass quietly; the next pass heals.
 */
class AgentEmbeddingSweeperIntegrationTest extends WholeAppIntegrationTest {

    private static final String MODEL = "text-embedding-3-small";
    private static final String SPEC = "{\"format\":\"JSON\",\"schema\":\"{}\",\"acceptanceCriteria\":\"ok\"}";

    @Autowired
    private AgentEmbeddingAppService agentEmbeddingAppService;

    private final FakeEmbeddingProvider provider = FakeEmbeddingProvider.shared();
    private AgentEmbeddingSweeper sweeper;

    @BeforeEach
    void setUp() {
        provider.reset();
        sweeper = new AgentEmbeddingSweeper(agentEmbeddingAppService);
        // Earlier tests in this context may have left Agents without a fresh vector; settle them
        // first so each test sees only its own Agents' requests.
        sweeper.sweep();
        provider.reset();
    }

    @AfterEach
    void restoreProvider() {
        provider.reset();
    }

    @Test
    void fillsAMissingVector() {
        UUID agentId = seedAgent("Seeded Agent", "Extracts invoices", "Line items and totals.");

        sweeper.sweep();

        String text = "Seeded Agent\nExtracts invoices\nLine items and totals.";
        assertThat(provider.requests()).extracting(FakeEmbeddingProvider.Recorded::input).containsExactly(text);
        assertThat(storedHash(agentId)).isEqualTo(Sha256.hex(text));
        assertThat(storedModel(agentId)).isEqualTo(MODEL);
    }

    @Test
    void replacesAVectorWhoseTextHashIsStale() {
        UUID agentId = seedAgent("Stale Agent", "Old tagline", null);
        sweeper.sweep();
        jdbc.update("UPDATE agent_profiles SET tagline = 'New tagline' WHERE agent_id = ?", agentId);
        provider.reset();

        sweeper.sweep();

        assertThat(provider.requests()).extracting(FakeEmbeddingProvider.Recorded::input)
                .containsExactly("Stale Agent\nNew tagline");
        assertThat(storedHash(agentId)).isEqualTo(Sha256.hex("Stale Agent\nNew tagline"));
    }

    @Test
    void replacesAVectorFromAnotherModel() {
        UUID agentId = seedAgent("Old Model Agent", null, null);
        sweeper.sweep();
        jdbc.update("UPDATE agent_embeddings SET model = 'text-embedding-ada-002' WHERE agent_id = ?", agentId);
        provider.reset();

        sweeper.sweep();

        assertThat(provider.requests()).hasSize(1);
        assertThat(storedModel(agentId)).isEqualTo(MODEL);
    }

    @Test
    void leavesFreshVectorsAndDeactivatedAgentsAlone() {
        seedAgent("Fresh Agent", "Tagline", "Description");
        sweeper.sweep();
        UUID deactivated = seedAgent("Retired Agent", null, null);
        jdbc.update("UPDATE agents SET status = 'DEACTIVATED' WHERE id = ?", deactivated);
        provider.reset();

        sweeper.sweep();

        assertThat(provider.requests()).isEmpty();
    }

    @Test
    void anOutageEndsThePassAndTheNextPassHeals() {
        UUID first = seedAgent("Outage Agent One", null, null);
        UUID second = seedAgent("Outage Agent Two", null, null);
        provider.down();

        sweeper.sweep();

        assertThat(provider.requests()).as("stops after the first failure").hasSize(1);
        assertThat(storedHash(first)).isNull();
        assertThat(storedHash(second)).isNull();

        provider.up();
        sweeper.sweep();

        assertThat(storedHash(first)).isEqualTo(Sha256.hex("Outage Agent One"));
        assertThat(storedHash(second)).isEqualTo(Sha256.hex("Outage Agent Two"));
    }

    private UUID seedAgent(String name, String tagline, String description) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", ownerId);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, ?, 'ACTIVE', ?, 80.00)", agentId, ownerId, name, versionId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, capability_categories, "
                        + "webhook_url, max_execution_seconds, price) VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, SPEC, new String[]{"x-" + UUID.randomUUID()},
                "https://agent.example.com/hook", new BigDecimal("10.00"));
        jdbc.update("INSERT INTO agent_profiles (agent_id, tagline, description) VALUES (?, ?, ?)",
                agentId, tagline, description);
        return agentId;
    }

    private String storedHash(UUID agentId) {
        return jdbc.query("SELECT text_hash FROM agent_embeddings WHERE agent_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, agentId);
    }

    private String storedModel(UUID agentId) {
        return jdbc.query("SELECT model FROM agent_embeddings WHERE agent_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, agentId);
    }
}
