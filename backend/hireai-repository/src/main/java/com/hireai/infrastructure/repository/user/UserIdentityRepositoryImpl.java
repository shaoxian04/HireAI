package com.hireai.infrastructure.repository.user;

import com.hireai.domain.biz.user.repository.UserIdentityRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Infrastructure impl of {@link UserIdentityRepository}. */
@Repository
public class UserIdentityRepositoryImpl implements UserIdentityRepository {

    private final UserIdentityJpaRepository jpa;

    public UserIdentityRepositoryImpl(UserIdentityJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<UUID> findUserIdByProviderSubject(String provider, String subject) {
        return jpa.findByProviderAndProviderSubject(provider, subject)
                .map(UserIdentityJpaEntity::getUserId);
    }

    @Override
    public void link(UUID userId, String provider, String subject, String emailAtLink) {
        jpa.save(new UserIdentityJpaEntity(UUID.randomUUID(), userId, provider, subject, emailAtLink));
    }
}
