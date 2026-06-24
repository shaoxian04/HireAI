package com.hireai.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the V5 seed: the BCrypt hash pasted into V5__seed_demo_users.sql MUST verify against the
 * documented demo password. If someone regenerates the migration or mistypes the hash, this fails
 * fast (pure unit test — no DB). Keep this hash byte-for-byte identical to the one in V5.
 */
class SeedDemoPasswordHashTest {

    /** Paste the EXACT same hash that is in V5__seed_demo_users.sql. */
    private static final String SEEDED_HASH = "$2a$10$x2486D5qTVSOkjdaS71oiuJZqFgGx2xK.Y//oon.LpOf6Ox4cEoN6";

    @Test
    void seededHashMatchesDemoPassword() {
        assertThat(new BCryptPasswordEncoder().matches("DemoPass123!", SEEDED_HASH)).isTrue();
    }
}
