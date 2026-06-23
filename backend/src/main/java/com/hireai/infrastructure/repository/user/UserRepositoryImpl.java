package com.hireai.infrastructure.repository.user;

import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure impl of {@link UserRepository}. Composes the user row with its role grants. */
@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpa;
    private final UserRoleJpaRepository roleJpa;

    public UserRepositoryImpl(UserJpaRepository userJpa, UserRoleJpaRepository roleJpa) {
        this.userJpa = userJpa;
        this.roleJpa = roleJpa;
    }

    @Override
    public Optional<UserModel> findByEmail(String email) {
        return userJpa.findByEmail(email).map(this::toModel);
    }

    @Override
    public Optional<UserModel> findById(UUID id) {
        return userJpa.findById(id).map(this::toModel);
    }

    @Override
    public UserModel create(UserModel user) {
        userJpa.save(new UserJpaEntity(user.id(), user.email(), user.passwordHash(),
                user.displayName(), user.active()));
        for (Role role : user.roles()) {
            roleJpa.save(new UserRoleJpaEntity(user.id(), role.name()));
        }
        return user;
    }

    @Override
    public void addRole(UUID userId, Role role) {
        // Race-safe idempotent grant (ON CONFLICT DO NOTHING) — adding a role twice is a no-op.
        roleJpa.insertIgnore(userId, role.name());
    }

    private UserModel toModel(UserJpaEntity e) {
        var roles = roleJpa.findByUserId(e.getId()).stream()
                .map(r -> Role.valueOf(r.getRole()))
                .collect(Collectors.toUnmodifiableSet());
        return new UserModel(e.getId(), e.getEmail(), e.getPasswordHash(), e.getDisplayName(),
                roles, e.isActive());
    }
}
