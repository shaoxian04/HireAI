package com.hireai.apikey;

import com.hireai.domain.biz.apikey.model.IdempotencyRecord;
import com.hireai.domain.biz.apikey.repository.IdempotencyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class IdempotencyRepositoryIntegrationTest {

    static boolean dockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired IdempotencyRepository repository;

    @Test
    void secondInsertWithSameOwnerAndKeyViolatesUnique() {
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        repository.insert(IdempotencyRecord.create(owner, "key-1", "fp-1", UUID.randomUUID(), now));

        assertThatThrownBy(() -> repository.insert(
                IdempotencyRecord.create(owner, "key-1", "fp-2", UUID.randomUUID(), now)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.find(owner, "key-1")).isPresent();
        assertThat(repository.find(owner, "key-1")).get()
                .extracting(IdempotencyRecord::requestFingerprint).isEqualTo("fp-1");
    }

    @Test
    void sameKeyDifferentOwnerIsIndependent() {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        repository.insert(IdempotencyRecord.create(UUID.randomUUID(), "shared", "fp", UUID.randomUUID(), now));
        repository.insert(IdempotencyRecord.create(UUID.randomUUID(), "shared", "fp", UUID.randomUUID(), now));
        // no exception → owner-scoped
    }
}
