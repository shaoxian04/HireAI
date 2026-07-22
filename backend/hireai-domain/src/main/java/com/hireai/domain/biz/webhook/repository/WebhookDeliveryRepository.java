package com.hireai.domain.biz.webhook.repository;

import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository {
    WebhookDeliveryModel save(WebhookDeliveryModel delivery);
    /** Ids of PENDING rows whose next_attempt_at <= now, oldest first, up to limit. No lock. */
    List<UUID> findDueIds(Instant now, int limit);
    /** Row-lock a still-due PENDING row (FOR UPDATE SKIP LOCKED). Empty if taken/handled. */
    Optional<WebhookDeliveryModel> claimForUpdate(UUID id, Instant now);
    Optional<WebhookDeliveryModel> findById(UUID id);
    /** Owner-scoped log read; nullable filters (since/status/taskId). */
    List<WebhookDeliveryModel> findForOwner(UUID ownerId, Instant since, String status, UUID taskId);
}
