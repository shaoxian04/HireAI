package com.hireai.webhook;

import com.hireai.application.biz.webhook.impl.WebhookDeliveryAppServiceImpl;
import com.hireai.application.port.webhook.WebhookSendResult;
import com.hireai.application.port.webhook.WebhookSenderPort;
import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.webhook.WebhookBackoffPolicy;
import com.hireai.domain.biz.webhook.enums.WebhookDeliveryStatus;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookDeliveryRepository;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookDeliveryAppServiceImplTest {
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final WebhookSubscriptionRepository subs = mock(WebhookSubscriptionRepository.class);
    private final WebhookUrlValidatorPort validator = mock(WebhookUrlValidatorPort.class);
    private final WebhookSenderPort sender = mock(WebhookSenderPort.class);
    private final WebhookBackoffPolicy backoff = new WebhookBackoffPolicy(10, 3600, 3);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC);
    private final WebhookDeliveryAppServiceImpl svc =
            new WebhookDeliveryAppServiceImpl(deliveries, subs, validator, sender, backoff, clock, 50);

    private final UUID id = UUID.randomUUID(), subId = UUID.randomUUID(), owner = UUID.randomUUID();

    private WebhookDeliveryModel pending() {
        return WebhookDeliveryModel.enqueue(id, UUID.randomUUID(), owner, subId,
                WebhookEventType.TASK_COMPLETED, "{\"x\":1}", "https://c/cb", clock.instant());
    }
    private WebhookSubscriptionModel sub() {
        return WebhookSubscriptionModel.rehydrate(subId, UUID.randomUUID(), owner, "https://c/cb", "whsec_s",
                true, clock.instant(), clock.instant());
    }

    @Test void notClaimableIsNoOp() {
        when(deliveries.claimForUpdate(eq(id), any())).thenReturn(Optional.empty());
        svc.attemptDelivery(id);
        verifyNoInteractions(sender);
        verify(deliveries, never()).save(any());
    }

    @Test void successMarksDelivered() {
        when(deliveries.claimForUpdate(eq(id), any())).thenReturn(Optional.of(pending()));
        when(subs.findById(subId)).thenReturn(Optional.of(sub()));
        when(sender.send(eq("https://c/cb"), eq("{\"x\":1}"), startsWith("t="), eq(id.toString()), eq("task.completed")))
                .thenReturn(WebhookSendResult.ok(200));
        svc.attemptDelivery(id);
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test void serverErrorBacksOff() {
        when(deliveries.claimForUpdate(eq(id), any())).thenReturn(Optional.of(pending()));
        when(subs.findById(subId)).thenReturn(Optional.of(sub()));
        when(sender.send(any(), any(), any(), any(), any())).thenReturn(WebhookSendResult.fail(500, "boom"));
        svc.attemptDelivery(id);
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(cap.getValue().attempts()).isEqualTo(1);
        assertThat(cap.getValue().lastError()).contains("500");
    }

    @Test void ssrfRejectionRecordsFailureWithoutSending() {
        when(deliveries.claimForUpdate(eq(id), any())).thenReturn(Optional.of(pending()));
        when(subs.findById(subId)).thenReturn(Optional.of(sub()));
        doThrow(new DomainException(ResultCode.VALIDATION_ERROR, "private")).when(validator).assertDeliverable(any());
        svc.attemptDelivery(id);
        verifyNoInteractions(sender);
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(cap.getValue().lastError()).contains("SSRF");
    }

    @Test void redeliverIsOwnerScoped() {
        WebhookDeliveryModel foreign = WebhookDeliveryModel.enqueue(id, UUID.randomUUID(),
                UUID.randomUUID(), subId, WebhookEventType.TASK_FAILED, "{}", "https://c/cb", clock.instant());
        when(deliveries.findById(id)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> svc.redeliver(owner, id))
                .isInstanceOf(DomainException.class); // owner mismatch -> NOT_FOUND
    }

    @Test void redeliverRequeuesOwnedRow() {
        when(deliveries.findById(id)).thenReturn(Optional.of(pending()));
        svc.redeliver(owner, id);
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(cap.getValue().attempts()).isZero();
    }
}
