package com.hireai.domain.biz.user.service.impl;

import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit test for the no-silent-link domain rule (account-takeover guard). */
class OAuthAccountLinkingDomainServiceImplTest {

    private final OAuthAccountLinkingDomainServiceImpl service = new OAuthAccountLinkingDomainServiceImpl();

    @Test
    void allowsCreationWhenNoLocalAccountExistsForEmail() {
        assertThatCode(() -> service.assertNoLocalAccountForEmail(Optional.empty(), "google"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenLocalAccountAlreadyExistsForEmail() {
        UserModel existing = new UserModel(
                UUID.randomUUID(), "ada@hireai.local", "h", "Ada", Set.of(Role.CLIENT), true);

        assertThatThrownBy(() -> service.assertNoLocalAccountForEmail(Optional.of(existing), "google"))
                .isInstanceOf(DomainException.class);
    }
}
