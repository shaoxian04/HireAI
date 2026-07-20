package com.hireai.application.biz.webhook.impl;

import com.hireai.application.biz.webhook.WebhookOutboxAppService;
import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.webhook.WebhookPayloads;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookDeliveryRepository;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Joins the caller's transaction (propagation REQUIRED) so the delivery row commits atomically with the
 * terminal state change — the transactional-outbox guarantee (no lost or phantom events). No-op for
 * WEB-submitted tasks (no api_key_task attribution) or API tasks whose key has no active subscription.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class WebhookOutboxAppServiceImpl implements WebhookOutboxAppService {

    private final ApiKeyTaskRepository apiKeyTaskRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final Clock clock;

    @Override
    public void enqueueCompleted(TaskModel task) {
        subscriptionFor(task.id()).ifPresent(sub -> {
            Instant now = clock.instant();
            UUID eventId = UUID.randomUUID();
            String payload = WebhookPayloads.completed(eventId, task.id(), now);
            enqueue(eventId, task, sub, WebhookEventType.TASK_COMPLETED, payload, now);
        });
    }

    @Override
    public void enqueueFailed(TaskModel task, String reason) {
        subscriptionFor(task.id()).ifPresent(sub -> {
            Instant now = clock.instant();
            UUID eventId = UUID.randomUUID();
            java.math.BigDecimal refunded = task.budget().value(); // WebhookPayloads.failed owns numeric rendering
            String payload = WebhookPayloads.failed(eventId, task.id(), reason, refunded, now);
            enqueue(eventId, task, sub, WebhookEventType.TASK_FAILED, payload, now);
        });
    }

    private Optional<WebhookSubscriptionModel> subscriptionFor(UUID taskId) {
        return apiKeyTaskRepository.findApiKeyIdByTask(taskId)
                .flatMap(subscriptionRepository::findActiveByApiKeyId);
    }

    private void enqueue(UUID eventId, TaskModel task, WebhookSubscriptionModel sub,
                         WebhookEventType type, String payload, Instant now) {
        deliveryRepository.save(WebhookDeliveryModel.enqueue(
                eventId, task.id(), sub.ownerId(), sub.id(), type, payload, sub.callbackUrl(), now));
        log.info("Enqueued {} webhook for task {} -> subscription {}", type.wire(), task.id(), sub.id());
    }
}
