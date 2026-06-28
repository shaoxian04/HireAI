package com.hireai.infrastructure.repository.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA persistence entity for an agent version (aggregate child). {@code output_spec} is
 * stored as JSONB (the @JdbcTypeCode + columnDefinition is required for String->jsonb
 * binding). {@code capability_categories} is a Postgres TEXT[] mapped to a List<String>.
 */
@Entity
@Table(name = "agent_versions")
public class AgentVersionDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_spec", columnDefinition = "jsonb", nullable = false)
    private String outputSpec;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "capability_categories", columnDefinition = "text[]", nullable = false)
    private List<String> capabilityCategories;

    @Column(name = "webhook_url", nullable = false)
    private String webhookUrl;

    @Column(name = "max_execution_seconds", nullable = false)
    private int maxExecutionSeconds;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    protected AgentVersionDO() {
    }

    public AgentVersionDO(UUID id, UUID agentId, int versionNumber, String outputSpec,
                                 List<String> capabilityCategories, String webhookUrl,
                                 int maxExecutionSeconds, BigDecimal price, Instant gmtCreate) {
        this.id = id;
        this.agentId = agentId;
        this.versionNumber = versionNumber;
        this.outputSpec = outputSpec;
        this.capabilityCategories = capabilityCategories;
        this.webhookUrl = webhookUrl;
        this.maxExecutionSeconds = maxExecutionSeconds;
        this.price = price;
        this.gmtCreate = gmtCreate;
    }

    public UUID getId() { return id; }
    public UUID getAgentId() { return agentId; }
    public int getVersionNumber() { return versionNumber; }
    public String getOutputSpec() { return outputSpec; }
    public List<String> getCapabilityCategories() { return capabilityCategories; }
    public String getWebhookUrl() { return webhookUrl; }
    public int getMaxExecutionSeconds() { return maxExecutionSeconds; }
    public BigDecimal getPrice() { return price; }
    public Instant getGmtCreate() { return gmtCreate; }
}
