package com.hireai.infrastructure.repository.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** JPA entity for one external identity link (user_identities). */
@Entity
@Table(name = "user_identities")
public class UserIdentityDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_subject", nullable = false)
    private String providerSubject;

    @Column(name = "email_at_link")
    private String emailAtLink;

    protected UserIdentityDO() {
    }

    public UserIdentityDO(UUID id, UUID userId, String provider, String providerSubject,
                                 String emailAtLink) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.emailAtLink = emailAtLink;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getProvider() { return provider; }
    public String getProviderSubject() { return providerSubject; }
    public String getEmailAtLink() { return emailAtLink; }
}
