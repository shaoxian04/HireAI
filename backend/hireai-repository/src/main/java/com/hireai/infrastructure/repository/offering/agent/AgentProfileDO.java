package com.hireai.infrastructure.repository.offering.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA persistence entity for agent_profiles (1:1 with agents). {@code gallery_urls} is a
 * Postgres TEXT[] mapped to a {@code List<String>} using the same technique as
 * {@link AgentVersionDO#getCapabilityCategories()}. Separate from the domain model so
 * the domain stays framework-free.
 */
@Entity
@Table(name = "agent_profiles")
public class AgentProfileDO {

    @Id
    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "tagline")
    private String tagline;

    @Column(name = "description")
    private String description;

    @Column(name = "sample_output")
    private String sampleOutput;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "cover_url")
    private String coverUrl;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "gallery_urls", columnDefinition = "text[]", nullable = false)
    private List<String> galleryUrls;

    @Column(name = "is_listed", nullable = false)
    private boolean listed;

    @Column(name = "is_featured", nullable = false)
    private boolean featured;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    @Column(name = "gmt_modified", nullable = false)
    private Instant gmtModified;

    protected AgentProfileDO() {
    }

    public AgentProfileDO(UUID agentId, String tagline, String description,
                                 String sampleOutput, String logoUrl, String coverUrl,
                                 List<String> galleryUrls, boolean listed, boolean featured,
                                 Instant gmtCreate, Instant gmtModified) {
        this.agentId = agentId;
        this.tagline = tagline;
        this.description = description;
        this.sampleOutput = sampleOutput;
        this.logoUrl = logoUrl;
        this.coverUrl = coverUrl;
        this.galleryUrls = galleryUrls;
        this.listed = listed;
        this.featured = featured;
        this.gmtCreate = gmtCreate;
        this.gmtModified = gmtModified;
    }

    public UUID getAgentId() { return agentId; }
    public String getTagline() { return tagline; }
    public String getDescription() { return description; }
    public String getSampleOutput() { return sampleOutput; }
    public String getLogoUrl() { return logoUrl; }
    public String getCoverUrl() { return coverUrl; }
    public List<String> getGalleryUrls() { return galleryUrls; }
    public boolean isListed() { return listed; }
    public boolean isFeatured() { return featured; }
    public Instant getGmtCreate() { return gmtCreate; }
    public Instant getGmtModified() { return gmtModified; }
}
