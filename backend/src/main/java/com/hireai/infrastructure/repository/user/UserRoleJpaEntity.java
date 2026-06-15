package com.hireai.infrastructure.repository.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** JPA entity for one (user_id, role) grant. */
@Entity
@Table(name = "user_roles")
@IdClass(UserRoleJpaEntity.Key.class)
public class UserRoleJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "role")
    private String role;

    protected UserRoleJpaEntity() {
    }

    public UserRoleJpaEntity(UUID userId, String role) {
        this.userId = userId;
        this.role = role;
    }

    public UUID getUserId() { return userId; }
    public String getRole() { return role; }

    /** Composite primary key. Must be a public class with a no-arg ctor + equals/hashCode. */
    public static class Key implements Serializable {
        private UUID userId;
        private String role;

        public Key() {
        }

        public Key(UUID userId, String role) {
            this.userId = userId;
            this.role = role;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(userId, key.userId) && Objects.equals(role, key.role);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, role);
        }
    }
}
