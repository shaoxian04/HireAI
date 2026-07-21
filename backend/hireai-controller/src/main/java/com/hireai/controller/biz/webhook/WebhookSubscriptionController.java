package com.hireai.controller.biz.webhook;

import com.hireai.application.biz.webhook.WebhookSubscriptionAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

record RegisterSubscriptionRequest(UUID apiKeyId, String callbackUrl) {}

record SubscriptionDTO(UUID id, UUID apiKeyId, String callbackUrl, String signingSecret,
                       boolean active, Instant createdAt, Instant updatedAt) {
    static SubscriptionDTO of(WebhookSubscriptionModel s) {
        return new SubscriptionDTO(s.id(), s.apiKeyId(), s.callbackUrl(), s.signingSecret(),
                s.active(), s.createdAt(), s.updatedAt());
    }
}

/**
 * Per-API-key webhook subscription management (register/get/rotate-secret/deactivate). JWT-only
 * (the security allow-list restricts {@code /api/webhooks/subscription/**} to {@code ROLE_CLIENT} —
 * a leaked API key must not be able to repoint the callback). Identity comes from
 * {@link CurrentUserProvider}; ownership of the target API key is enforced in the app service
 * (Invariant #5). The register response echoes the {@code signingSecret} once so the client can
 * verify inbound signatures.
 */
@RestController
@RequestMapping("/api/webhooks/subscription")
public class WebhookSubscriptionController extends BaseController {

    private final WebhookSubscriptionAppService service;
    private final CurrentUserProvider currentUser;

    public WebhookSubscriptionController(WebhookSubscriptionAppService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping
    public WebResult<SubscriptionDTO> register(@RequestBody RegisterSubscriptionRequest req) {
        return ok(SubscriptionDTO.of(service.register(currentUser.currentUserId(), req.apiKeyId(), req.callbackUrl())));
    }

    @GetMapping
    public WebResult<SubscriptionDTO> get(@RequestParam("apiKeyId") UUID apiKeyId) {
        return ok(SubscriptionDTO.of(service.get(currentUser.currentUserId(), apiKeyId)));
    }

    @PostMapping("/rotate-secret")
    public WebResult<SubscriptionDTO> rotate(@RequestParam("apiKeyId") UUID apiKeyId) {
        return ok(SubscriptionDTO.of(service.rotateSecret(currentUser.currentUserId(), apiKeyId)));
    }

    @PostMapping("/deactivate")
    public WebResult<Void> deactivate(@RequestParam("apiKeyId") UUID apiKeyId) {
        service.deactivate(currentUser.currentUserId(), apiKeyId);
        return ok(null);
    }
}
