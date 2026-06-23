package com.hireai.review;

import com.hireai.domain.biz.review.model.ReviewModel;
import com.hireai.domain.biz.review.repository.ReviewRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1–V7.
 * Verifies the Review persistence slice end-to-end: seeded reviews, ordering, and
 * builder-response round-trip.
 *
 * Each test creates its own user + agent row via JdbcTemplate so the shared container
 * carries no cross-test state.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class ReviewRepositoryIntegrationTest {

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

    @Autowired ReviewRepository reviewRepository;
    @Autowired JdbcTemplate jdbc;

    /** Insert a CLIENT user, a BUILDER user, and an ACTIVE agent; return agent id + client id. */
    private SeedResult seedClientAndAgent() {
        UUID clientId = UUID.randomUUID();
        UUID builderId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)",
                clientId, clientId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", clientId);
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)",
                builderId, builderId + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", builderId);
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, reputation_score) " +
                        "VALUES (?, ?, 'Test Agent', 'ACTIVE', 50.00)",
                agentId, builderId);
        return new SeedResult(clientId, agentId);
    }

    record SeedResult(UUID clientId, UUID agentId) {}

    @Test
    void savesAndListsPublishedNewestFirst() {
        SeedResult seed = seedClientAndAgent();
        UUID clientId = seed.clientId();
        UUID agentId = seed.agentId();

        // First review: rating 5, "great" — older (default Instant.now() from seeded())
        ReviewModel first = ReviewModel.seeded(clientId, agentId, 5, "great");
        reviewRepository.save(first);

        // Second review: rating 3, "ok" — explicitly later so ordering is deterministic
        ReviewModel second = new ReviewModel(
                UUID.randomUUID(), null, clientId, agentId, 3,
                "ok", null, true, Instant.now().plusSeconds(1));
        reviewRepository.save(second);

        List<ReviewModel> results = reviewRepository.findPublishedByAgentId(agentId, 10);

        assertThat(results).hasSize(2);
        // Newest first — "ok" (plusSeconds(1)) should come before "great"
        assertThat(results.get(0).reviewText()).isEqualTo("ok");
        assertThat(results.get(0).rating()).isEqualTo(3);
        assertThat(results.get(1).reviewText()).isEqualTo("great");
        assertThat(results.get(1).rating()).isEqualTo(5);
    }

    @Test
    void respondPersistsBuilderResponse() {
        SeedResult seed = seedClientAndAgent();
        UUID clientId = seed.clientId();
        UUID agentId = seed.agentId();

        ReviewModel review = ReviewModel.seeded(clientId, agentId, 5, "Excellent result.");
        reviewRepository.save(review);

        ReviewModel withResponse = review.respond("Thanks — glad it helped!");
        reviewRepository.save(withResponse);

        Optional<ReviewModel> loaded = reviewRepository.findById(withResponse.id());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().builderResponse()).isEqualTo("Thanks — glad it helped!");
        assertThat(loaded.get().rating()).isEqualTo(5);
        assertThat(loaded.get().reviewText()).isEqualTo("Excellent result.");
        assertThat(loaded.get().agentId()).isEqualTo(agentId);
    }

    @Test
    void gmtCreatePreservedOnRespond() {
        SeedResult seed = seedClientAndAgent();
        UUID clientId = seed.clientId();
        UUID agentId = seed.agentId();

        ReviewModel review = ReviewModel.seeded(clientId, agentId, 4, "Solid work.");
        reviewRepository.save(review);

        // Capture gmt_create after the first write
        java.sql.Timestamp gmtCreateBefore = jdbc.queryForObject(
                "SELECT gmt_create FROM reviews WHERE id = ?",
                java.sql.Timestamp.class, review.id());

        // Builder responds — triggers a second save (upsert)
        reviewRepository.save(review.respond("Thanks!"));

        // gmt_create must be unchanged across the upsert
        java.sql.Timestamp gmtCreateAfter = jdbc.queryForObject(
                "SELECT gmt_create FROM reviews WHERE id = ?",
                java.sql.Timestamp.class, review.id());
        assertThat(gmtCreateAfter).isEqualTo(gmtCreateBefore);
    }

    @Test
    void findPublishedExcludesUnpublished() {
        SeedResult seed = seedClientAndAgent();
        UUID clientId = seed.clientId();
        UUID agentId = seed.agentId();

        // Published review — visible
        ReviewModel published = ReviewModel.seeded(clientId, agentId, 5, "Visible review.");
        reviewRepository.save(published);

        // Unpublished review — must be excluded from findPublishedByAgentId
        ReviewModel unpublished = new ReviewModel(
                UUID.randomUUID(), null, clientId, agentId, 3,
                "Hidden review.", null, false, Instant.now());
        reviewRepository.save(unpublished);

        List<ReviewModel> results = reviewRepository.findPublishedByAgentId(agentId, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).reviewText()).isEqualTo("Visible review.");
        assertThat(results.get(0).published()).isTrue();
    }
}
