package com.hireai.webhook;

import com.hireai.application.biz.apikey.ApiKeyAuthService;
import com.hireai.application.biz.webhook.WebhookDeliveryAppService;
import com.hireai.application.biz.webhook.WebhookSubscriptionAppService;
import com.hireai.application.port.security.JwtService;
import com.hireai.controller.biz.webhook.WebhookDeliveryController;
import com.hireai.controller.biz.webhook.WebhookSubscriptionController;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.webhook.enums.WebhookDeliveryStatus;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test for {@link WebhookSubscriptionController} and {@link WebhookDeliveryController}.
 * Mirrors {@code DisputeControllerTest}'s security-slice setup: the default (prod-like) secured
 * filter chain — no {@code @ActiveProfiles("test")} — so anonymous requests actually 401.
 */
@WebMvcTest(controllers = {WebhookSubscriptionController.class, WebhookDeliveryController.class})
@Import(SecurityConfig.class)
class WebhookControllerSliceTest {

    @Autowired MockMvc mvc;

    @MockBean WebhookSubscriptionAppService subscriptionAppService;
    @MockBean WebhookDeliveryAppService deliveryAppService;
    @MockBean CurrentUserProvider currentUserProvider;
    @MockBean JwtService jwtService; // required to wire the secured filter chain
    @MockBean ApiKeyAuthService apiKeyAuthService; // also required by the secured chain

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID API_KEY_ID = UUID.randomUUID();
    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();

    private WebhookSubscriptionModel subscription() {
        return WebhookSubscriptionModel.create(UUID.randomUUID(), API_KEY_ID, OWNER_ID,
                "https://client.example.com/hooks", "whsec_RAWSECRET",
                Instant.parse("2026-07-15T10:00:00Z"));
    }

    private WebhookDeliveryModel delivery() {
        return WebhookDeliveryModel.rehydrate(DELIVERY_ID, TASK_ID, OWNER_ID, API_KEY_ID,
                WebhookEventType.TASK_COMPLETED, "{}", "https://client.example.com/hooks",
                WebhookDeliveryStatus.DELIVERED, 1, null,
                null, Instant.parse("2026-07-15T10:00:00Z"), Instant.parse("2026-07-15T10:00:05Z"));
    }

    @Test
    void register_anonymous_401() throws Exception {
        mvc.perform(post("/api/webhooks/subscription").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKeyId\":\"" + API_KEY_ID + "\",\"callbackUrl\":\"https://client.example.com/hooks\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CLIENT")
    void register_clientReturnsSecretEchoed() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(subscriptionAppService.register(eq(OWNER_ID), eq(API_KEY_ID), eq("https://client.example.com/hooks")))
                .thenReturn(subscription());

        mvc.perform(post("/api/webhooks/subscription").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKeyId\":\"" + API_KEY_ID + "\",\"callbackUrl\":\"https://client.example.com/hooks\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.signingSecret").value("whsec_RAWSECRET"))
                .andExpect(jsonPath("$.data.callbackUrl").value("https://client.example.com/hooks"))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void listDeliveries_anonymous_401() throws Exception {
        mvc.perform(get("/api/webhooks/deliveries")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CLIENT")
    void listDeliveries_clientReturnsMappedRows() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(deliveryAppService.listForOwner(eq(OWNER_ID), any(), isNull(), isNull()))
                .thenReturn(List.of(delivery()));

        mvc.perform(get("/api/webhooks/deliveries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(DELIVERY_ID.toString()))
                .andExpect(jsonPath("$.data[0].taskId").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.data[0].eventType").value("task.completed"))
                .andExpect(jsonPath("$.data[0].status").value("DELIVERED"));
    }

    @Test
    @WithMockUser(roles = "CLIENT")
    void redeliver_delegates() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);

        mvc.perform(post("/api/webhooks/deliveries/{id}/redeliver", DELIVERY_ID).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(deliveryAppService).redeliver(eq(OWNER_ID), eq(DELIVERY_ID));
    }
}
