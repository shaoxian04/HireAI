package com.hireai.adjudication;

import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V16,
 * creating the validation_reports table. Verifies that the ValidationReportModel
 * aggregate round-trips through the repository (save → findByTaskIdAndAttemptNo),
 * including JSONB round-trip for the checks list.
 *
 * Skipped (not failed) when no Docker daemon is reachable — CI-gated.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class ValidationReportRepositoryIntegrationTest {

    /** Skip (do not fail) the whole class when no Docker daemon is reachable. */
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

    @Autowired
    ValidationReportRepository repo;

    @Test
    void roundTrips() {
        UUID taskId = UUID.randomUUID();
        var saved = repo.save(ValidationReportModel.of(taskId, 1, List.of(
                new CheckResult("FORMAT_JSON_PARSEABLE", true, "ok"),
                new CheckResult("SCHEMA_SKIPPED", true, "no schema"))));
        var found = repo.findByTaskIdAndAttemptNo(taskId, 1).orElseThrow();
        assertThat(found.verdict()).isEqualTo(Verdict.PASS);
        assertThat(found.checks()).hasSize(2);
        assertThat(found.checks().get(0).rule()).isEqualTo("FORMAT_JSON_PARSEABLE");
        assertThat(found.id()).isEqualTo(saved.id());
    }
}
