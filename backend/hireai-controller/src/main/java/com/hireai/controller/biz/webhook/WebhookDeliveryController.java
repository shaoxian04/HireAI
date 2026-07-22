package com.hireai.controller.biz.webhook;

import com.hireai.application.biz.webhook.WebhookDeliveryAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

record DeliveryDTO(UUID eventId, UUID taskId, String eventType, String status, int attempts,
                   Instant nextAttemptAt, Instant createdAt, Instant deliveredAt, String lastError) {
    static DeliveryDTO of(WebhookDeliveryModel d) {
        return new DeliveryDTO(d.id(), d.taskId(), d.eventType().wire(), d.status().name(), d.attempts(),
                d.nextAttemptAt(), d.createdAt(), d.deliveredAt(), d.lastError());
    }
}

/**
 * Owner-scoped webhook delivery log ({@code GET} list with {@code since}/{@code status}/{@code taskId}
 * filters) and manual replay ({@code redeliver}). Reachable by a human CLIENT or an API_CLIENT key
 * (reconcile/replay headless). Identity comes from {@link CurrentUserProvider}; ownership of the
 * target delivery is enforced in the app service (Invariant #5).
 */
@RestController
@RequestMapping("/api/webhooks/deliveries")
public class WebhookDeliveryController extends BaseController {

    private final WebhookDeliveryAppService service;
    private final CurrentUserProvider currentUser;

    public WebhookDeliveryController(WebhookDeliveryAppService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public WebResult<List<DeliveryDTO>> list(
            @RequestParam(value = "since", required = false) Instant since,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "taskId", required = false) UUID taskId) {
        Instant from = since != null ? since : Instant.now().minus(7, ChronoUnit.DAYS);
        List<DeliveryDTO> rows = service.listForOwner(currentUser.currentUserId(), from, status, taskId)
                .stream().map(DeliveryDTO::of).toList();
        return ok(rows);
    }

    @PostMapping("/{id}/redeliver")
    public WebResult<Void> redeliver(@PathVariable("id") UUID id) {
        service.redeliver(currentUser.currentUserId(), id);
        return ok(null);
    }
}
