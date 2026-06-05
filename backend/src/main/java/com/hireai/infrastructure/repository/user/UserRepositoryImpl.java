package com.hireai.infrastructure.repository.user;

import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Infrastructure implementation of the domain {@link UserRepository}. Maps a {@code UserJpaEntity}
 * to the framework-free {@code UserModel}, translating the {@code role} TEXT column to the {@link Role}
 * enum.
 */
@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpa;

    public UserRepositoryImpl(UserJpaRepository userJpa) {
        this.userJpa = userJpa;
    }

    @Override
    public Optional<UserModel> findByEmail(String email) {
        return userJpa.findByEmail(email).map(this::toModel);
    }

    private UserModel toModel(UserJpaEntity e) {
        return new UserModel(e.getId(), e.getEmail(), e.getPasswordHash(),
                Role.valueOf(e.getRole()), e.isActive());
    }
}
