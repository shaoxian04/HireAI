package com.hireai.application.biz.task.webhookdelivery;

import com.hireai.domain.biz.task.webhookdelivery.model.WebhookDeliveryModel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryAppService {
    List<UUID> dueDeliveryIds();
    void attemptDelivery(UUID id);
    List<WebhookDeliveryModel> listForOwner(UUID ownerId, Instant since, String status, UUID taskId);
    void redeliver(UUID ownerId, UUID deliveryId);
}
