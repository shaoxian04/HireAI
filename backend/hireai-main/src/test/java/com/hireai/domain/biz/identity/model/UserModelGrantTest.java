package com.hireai.domain.biz.identity.model;

import com.hireai.domain.biz.identity.enums.Role;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserModelGrantTest {

    private UserModel client() {
        return new UserModel(UUID.randomUUID(), "ada@hireai.local", Credential.ofHash("h"),
                "Ada", Set.of(Role.CLIENT), true);
    }

    @Test
    void grantAddsTheRole() {
        UserModel upgraded = client().grant(Role.BUILDER);
        assertThat(upgraded.roles()).containsExactlyInAnyOrder(Role.CLIENT, Role.BUILDER);
    }

    @Test
    void grantIsIdempotentAndDoesNotMutateTheOriginal() {
        UserModel original = client();
        UserModel twice = original.grant(Role.BUILDER).grant(Role.BUILDER);
        assertThat(twice.roles()).containsExactlyInAnyOrder(Role.CLIENT, Role.BUILDER);
        assertThat(original.roles()).containsExactly(Role.CLIENT);
    }
}
