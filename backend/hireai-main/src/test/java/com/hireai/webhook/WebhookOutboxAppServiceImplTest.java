package com.hireai.webhook;

import com.hireai.application.biz.webhook.impl.WebhookOutboxAppServiceImpl;
import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookDeliveryRepository;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WebhookOutboxAppServiceImplTest {

    private final ApiKeyTaskRepository apiKeyTasks = mock(ApiKeyTaskRepository.class);
    private final WebhookSubscriptionRepository subs = mock(WebhookSubscriptionRepository.class);
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-19T09:12:00Z"), ZoneOffset.UTC);
    private final WebhookOutboxAppServiceImpl svc =
            new WebhookOutboxAppServiceImpl(apiKeyTasks, subs, deliveries, clock);

    private final UUID taskId = UUID.randomUUID(), clientId = UUID.randomUUID(),
            keyId = UUID.randomUUID(), subId = UUID.randomUUID();

    private TaskModel task() {
        TaskModel t = mock(TaskModel.class);
        when(t.id()).thenReturn(taskId);
        when(t.clientId()).thenReturn(clientId);
        when(t.budget()).thenReturn(Money.of("120"));
        return t;
    }
    private WebhookSubscriptionModel sub() {
        return WebhookSubscriptionModel.rehydrate(subId, keyId, clientId, "https://c/cb", "whsec_x",
                true, Instant.now(), Instant.now());
    }

    @Test void webTaskEnqueuesNothing() {
        when(apiKeyTasks.findApiKeyIdByTask(taskId)).thenReturn(Optional.empty());
        svc.enqueueCompleted(task());
        verifyNoInteractions(deliveries);
    }
    @Test void apiTaskWithNoActiveSubEnqueuesNothing() {
        when(apiKeyTasks.findApiKeyIdByTask(taskId)).thenReturn(Optional.of(keyId));
        when(subs.findActiveByApiKeyId(keyId)).thenReturn(Optional.empty());
        svc.enqueueCompleted(task());
        verifyNoInteractions(deliveries);
    }
    @Test void completedEnqueuesPendingTaskCompleted() {
        when(apiKeyTasks.findApiKeyIdByTask(taskId)).thenReturn(Optional.of(keyId));
        when(subs.findActiveByApiKeyId(keyId)).thenReturn(Optional.of(sub()));
        svc.enqueueCompleted(task());
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        WebhookDeliveryModel d = cap.getValue();
        assertThat(d.eventType()).isEqualTo(WebhookEventType.TASK_COMPLETED);
        assertThat(d.taskId()).isEqualTo(taskId);
        assertThat(d.ownerId()).isEqualTo(clientId);
        assertThat(d.subscriptionId()).isEqualTo(subId);
        assertThat(d.targetUrl()).isEqualTo("https://c/cb");
        assertThat(d.payload()).contains("\"type\":\"task.completed\"").contains(taskId.toString());
    }
    @Test void failedEnqueuesReasonAndRefund() {
        when(apiKeyTasks.findApiKeyIdByTask(taskId)).thenReturn(Optional.of(keyId));
        when(subs.findActiveByApiKeyId(keyId)).thenReturn(Optional.of(sub()));
        svc.enqueueFailed(task(), "SPEC_VIOLATION");
        ArgumentCaptor<WebhookDeliveryModel> cap = ArgumentCaptor.forClass(WebhookDeliveryModel.class);
        verify(deliveries).save(cap.capture());
        assertThat(cap.getValue().eventType()).isEqualTo(WebhookEventType.TASK_FAILED);
        assertThat(cap.getValue().payload())
                .contains("\"reason\":\"SPEC_VIOLATION\"").contains("\"refunded\":120.00");
    }
}
