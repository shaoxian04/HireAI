package com.hireai.domain.biz.user.service;

import com.hireai.domain.biz.user.model.UserModel;

import java.util.Optional;

/**
 * Domain rule governing how an external (OAuth) identity attaches to a local account.
 *
 * <p>Invariant: an OAuth identity may only seed a brand-new account. It must never be silently
 * linked to a pre-existing local account discovered by email, because local emails are not
 * independently verified (registration does not prove ownership) — silent linking would let an
 * attacker who pre-registered a victim's email capture the victim's later OAuth login (account
 * takeover). Linking to an existing account must go through an explicit, password-authenticated
 * flow.
 *
 * <p>Framework-free; the bean is registered in {@code DomainServiceConfig}. The application layer
 * performs the read-only lookup and passes the result in; this service owns the decision.
 */
public interface OAuthAccountLinkingDomainService {

    /**
     * Guards the create-account-on-first-OAuth-login path.
     *
     * @param existingByEmail the local account already holding the identity's email, if any
     *                        (looked up by the application via the {@code UserRepository} read port)
     * @param provider        the OAuth provider id, used in the rejection message
     * @throws com.hireai.utility.exception.DomainException if a local account already exists
     */
    void assertNoLocalAccountForEmail(Optional<UserModel> existingByEmail, String provider);
}
