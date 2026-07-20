package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.webhook.WebhookDeliveryAppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Delivers due webhook rows. Each {@code attemptDelivery(id)} is a cross-bean call (own transaction),
 * so one poisoned row can't roll back others in the same pass, and the row-level FOR UPDATE SKIP LOCKED
 * makes it safe under multiple instances. Delivery never touches money/task tables.
 */
@Slf4j
@Component
@Profile("!test")
public class WebhookDeliverySweeper {

    private final WebhookDeliveryAppService deliveryAppService;

    public WebhookDeliverySweeper(WebhookDeliveryAppService deliveryAppService) {
        this.deliveryAppService = deliveryAppService;
    }

    @Scheduled(fixedDelayString = "${hireai.webhooks.sweep-interval:PT5S}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: one delivery pass. */
    void sweep() {
        List<UUID> due = deliveryAppService.dueDeliveryIds();
        for (UUID id : due) {
            try {
                deliveryAppService.attemptDelivery(id);
            } catch (Exception e) {
                log.warn("Webhook sweeper: delivery {} failed unexpectedly", id, e);
            }
        }
        if (!due.isEmpty()) log.info("Webhook sweeper: attempted {} delivery(ies)", due.size());
    }
}
