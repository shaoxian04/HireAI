package com.hireai.infrastructure.repository.identity;

import com.hireai.domain.biz.identity.model.OAuthIdentity;
import com.hireai.domain.biz.identity.repository.OAuthIdentityRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Infrastructure impl of {@link OAuthIdentityRepository}. Maps onto the user_identities table. */
@Repository
public class OAuthIdentityRepositoryImpl implements OAuthIdentityRepository {

    private final UserIdentityJpaRepository jpa;

    public OAuthIdentityRepositoryImpl(UserIdentityJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<UUID> findUserIdByProviderSubject(String provider, String subject) {
        return jpa.findByProviderAndProviderSubject(provider, subject)
                .map(UserIdentityDO::getUserId);
    }

    @Override
    public void save(OAuthIdentity identity) {
        jpa.save(new UserIdentityDO(identity.id(), identity.userId(), identity.provider(),
                identity.subject(), identity.emailAtLink()));
    }
}
