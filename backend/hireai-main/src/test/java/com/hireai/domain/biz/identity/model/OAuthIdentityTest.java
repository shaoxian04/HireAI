package com.hireai.domain.biz.identity.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthIdentityTest {

    @Test
    void linkMintsAnIdentityWithAFreshIdForTheUser() {
        UUID userId = UUID.randomUUID();
        OAuthIdentity identity = OAuthIdentity.link(userId, "google", "sub-123", "ada@hireai.local");

        assertThat(identity.id()).isNotNull();
        assertThat(identity.userId()).isEqualTo(userId);
        assertThat(identity.provider()).isEqualTo("google");
        assertThat(identity.subject()).isEqualTo("sub-123");
        assertThat(identity.emailAtLink()).isEqualTo("ada@hireai.local");
    }
}
