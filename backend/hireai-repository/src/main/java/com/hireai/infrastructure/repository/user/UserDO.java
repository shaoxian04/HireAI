package com.hireai.infrastructure.repository.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** JPA persistence entity for a user row. Roles live in {@link UserRoleDO}. */
@Entity
@Table(name = "users")
public class UserDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected UserDO() {
    }

    public UserDO(UUID id, String email, String passwordHash, String displayName, boolean active) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.active = active;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public boolean isActive() { return active; }
}
