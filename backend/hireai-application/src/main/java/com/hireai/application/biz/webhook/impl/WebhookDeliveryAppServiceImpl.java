package com.hireai.application.biz.webhook.impl;

import com.hireai.application.biz.webhook.WebhookDeliveryAppService;
import com.hireai.application.port.webhook.WebhookSendResult;
import com.hireai.application.port.webhook.WebhookSenderPort;
import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.webhook.WebhookBackoffPolicy;
import com.hireai.domain.biz.webhook.WebhookSignature;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookDeliveryRepository;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Delivery loop: claim (FOR UPDATE SKIP LOCKED) -> SSRF re-check -> sign -> send -> mark/backoff.
 * {@code attemptDelivery} is its own transaction, invoked cross-bean from {@code WebhookDeliverySweeper}
 * (mirrors {@code RulingAcceptSweeper}) so each id's claim+process+save runs atomically under its row
 * lock, and one poisoned row can't roll back others in the same sweep pass.
 */
@Service
@Slf4j
public class WebhookDeliveryAppServiceImpl implements WebhookDeliveryAppService {

    private final WebhookDeliveryRepository deliveries;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookUrlValidatorPort urlValidator;
    private final WebhookSenderPort sender;
    private final WebhookBackoffPolicy backoff;
    private final Clock clock;
    private final int batchSize;

    public WebhookDeliveryAppServiceImpl(WebhookDeliveryRepository deliveries,
            WebhookSubscriptionRepository subscriptions, WebhookUrlValidatorPort urlValidator,
            WebhookSenderPort sender, WebhookBackoffPolicy backoff, Clock clock,
            @Value("${hireai.webhooks.batch-size:50}") int batchSize) {
        this.deliveries = deliveries; this.subscriptions = subscriptions; this.urlValidator = urlValidator;
        this.sender = sender; this.backoff = backoff; this.clock = clock; this.batchSize = batchSize;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> dueDeliveryIds() {
        return deliveries.findDueIds(clock.instant(), batchSize);
    }

    @Override
    @Transactional
    public void attemptDelivery(UUID id) {
        Instant now = clock.instant();
        var claimed = deliveries.claimForUpdate(id, now);
        if (claimed.isEmpty()) return; // taken by another sweeper / already handled
        WebhookDeliveryModel d = claimed.get();

        WebhookSubscriptionModel sub = subscriptions.findById(d.subscriptionId()).orElse(null);
        if (sub == null || !sub.active()) {
            deliveries.save(d.recordFailure(now, "subscription inactive", backoff));
            return;
        }
        try {
            urlValidator.assertDeliverable(d.targetUrl());
        } catch (DomainException e) {
            deliveries.save(d.recordFailure(now, "SSRF: " + e.getMessage(), backoff));
            return;
        }
        String header = WebhookSignature.header(sub.signingSecret(), now.getEpochSecond(), d.payload());
        WebhookSendResult r = sender.send(d.targetUrl(), d.payload(), header,
                d.id().toString(), d.eventType().wire());
        if (r.success()) {
            deliveries.save(d.markDelivered(now));
        } else {
            String err = "HTTP " + r.statusCode() + (r.error() != null ? ": " + r.error() : "");
            deliveries.save(d.recordFailure(now, err, backoff));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDeliveryModel> listForOwner(UUID ownerId, Instant since, String status, UUID taskId) {
        return deliveries.findForOwner(ownerId, since, status, taskId);
    }

    @Override
    @Transactional
    public void redeliver(UUID ownerId, UUID deliveryId) {
        WebhookDeliveryModel d = deliveries.findById(deliveryId)
                .filter(x -> x.ownerId().equals(ownerId))
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Delivery not found: " + deliveryId));
        deliveries.save(d.requeue(clock.instant()));
    }
}
