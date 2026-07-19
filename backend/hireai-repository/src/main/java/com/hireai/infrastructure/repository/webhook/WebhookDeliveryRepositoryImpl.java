package com.hireai.infrastructure.repository.webhook;

import com.hireai.domain.biz.webhook.enums.WebhookDeliveryStatus;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.repository.WebhookDeliveryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class WebhookDeliveryRepositoryImpl implements WebhookDeliveryRepository {
    private final WebhookDeliveryJpaRepository jpa;
    @PersistenceContext private EntityManager em;

    public WebhookDeliveryRepositoryImpl(WebhookDeliveryJpaRepository jpa) { this.jpa = jpa; }

    private WebhookEventType type(String s) {
        for (WebhookEventType t : WebhookEventType.values()) if (t.wire().equals(s) || t.name().equals(s)) return t;
        throw new IllegalStateException("Unknown event type " + s);
    }

    @Override public WebhookDeliveryModel save(WebhookDeliveryModel d) {
        jpa.save(new WebhookDeliveryDO(d.id(), d.taskId(), d.ownerId(), d.subscriptionId(),
                d.eventType().wire(), d.payload(), d.targetUrl(), d.status().name(), d.attempts(),
                d.nextAttemptAt(), d.lastError(), d.createdAt(), d.deliveredAt()));
        return d;
    }
    @Override public List<UUID> findDueIds(Instant now, int limit) { return jpa.findDueIds(now, limit); }

    @Override public Optional<WebhookDeliveryModel> claimForUpdate(UUID id, Instant now) {
        return jpa.claimForUpdate(id, now).map(this::toModel);
    }
    @Override public Optional<WebhookDeliveryModel> findById(UUID id) { return jpa.findById(id).map(this::toModel); }

    @Override public List<WebhookDeliveryModel> findForOwner(UUID ownerId, Instant since, String status, UUID taskId) {
        // Dynamic filter via a native query with optional predicates (nulls ignored). A null `since`
        // means "no time floor" — it must be omitted, not bound as `created_at >= NULL` (which is
        // UNKNOWN for every row in Postgres and would wrongly return an empty list).
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM webhook_deliveries WHERE owner_id = :owner");
        if (since != null) sql.append(" AND created_at >= :since");
        if (status != null) sql.append(" AND status = :status");
        if (taskId != null) sql.append(" AND task_id = :taskId");
        sql.append(" ORDER BY created_at DESC");
        var q = em.createNativeQuery(sql.toString(), WebhookDeliveryDO.class)
                .setParameter("owner", ownerId);
        if (since != null) q.setParameter("since", since);
        if (status != null) q.setParameter("status", status);
        if (taskId != null) q.setParameter("taskId", taskId);
        @SuppressWarnings("unchecked")
        List<WebhookDeliveryDO> rows = q.getResultList();
        return rows.stream().map(this::toModel).collect(Collectors.toList());
    }

    private WebhookDeliveryModel toModel(WebhookDeliveryDO d) {
        return WebhookDeliveryModel.rehydrate(d.getId(), d.getTaskId(), d.getOwnerId(), d.getSubscriptionId(),
                type(d.getEventType()), d.getPayload(), d.getTargetUrl(),
                WebhookDeliveryStatus.valueOf(d.getStatus()), d.getAttempts(), d.getNextAttemptAt(),
                d.getLastError(), d.getCreatedAt(), d.getDeliveredAt());
    }
}
