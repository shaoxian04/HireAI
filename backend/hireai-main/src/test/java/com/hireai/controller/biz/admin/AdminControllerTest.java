package com.hireai.controller.biz.admin;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.admin.AdminReadAppService;
import com.hireai.application.biz.admin.view.AdminViews;
import com.hireai.application.port.security.JwtService;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
class AdminControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean AdminReadAppService adminReadAppService;
    @MockBean DisputeAppService disputeAppService;
    @MockBean CurrentUserProvider currentUserProvider;
    @MockBean JwtService jwtService; // required to wire the secured filter chain

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    @WithAnonymousUser
    void overviewRejectsAnonymousWith401() throws Exception {
        mockMvc.perform(get("/api/admin/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CLIENT")
    void overviewRejectsNonAdminWith403() throws Exception {
        mockMvc.perform(get("/api/admin/overview")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void overviewAllowsAdmin() throws Exception {
        when(adminReadAppService.overview()).thenReturn(new AdminViews.Overview(
                1, 0, 2, 3, 10, 4, 2, new BigDecimal("20.00"), new BigDecimal("1.50")));
        mockMvc.perform(get("/api/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.disputesEscalated").value(2))
                .andExpect(jsonPath("$.data.escrowHeld").value(20.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void ruleDelegatesToAdminRuleWithJwtIdentity() throws Exception {
        UUID disputeId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(ADMIN_ID);
        when(adminReadAppService.disputeDetail(disputeId)).thenReturn(new AdminViews.DisputeDetail(
                disputeId, UUID.randomUUID(), "t", "d", "RESOLVED", "A_MISMATCH",
                java.time.Instant.parse("2026-07-02T00:00:00Z"), "client", null, null, null, null,
                false, List.of()));

        mockMvc.perform(post("/api/admin/disputes/{id}/rule", disputeId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"NOT_FULFILLED\",\"rationale\":\"backstop refund\"}"))
                .andExpect(status().isOk());

        verify(disputeAppService).adminRule(eq(disputeId),
                eq(com.hireai.domain.biz.adjudication.enums.RulingCategory.NOT_FULFILLED),
                eq("backstop refund"), eq(ADMIN_ID));
    }
}
