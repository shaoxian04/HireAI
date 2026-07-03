package com.hireai.controller.biz.adjudication;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.dispute.DisputeReadAppService;
import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;
import com.hireai.application.port.security.JwtService;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.task.enums.RejectReason;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test for the client-facing accept-ruling/appeal/mine endpoints. Mirrors
 * {@code AdminControllerTest}'s security-slice setup: the default (prod-like) secured filter
 * chain — no {@code @ActiveProfiles("test")} — so anonymous requests actually 401.
 */
@WebMvcTest(DisputeController.class)
@Import(SecurityConfig.class)
class DisputeControllerTest {

    @Autowired MockMvc mvc;

    @MockBean DisputeReadAppService disputeReadAppService;
    @MockBean DisputeAppService disputeAppService;
    @MockBean CurrentUserProvider currentUserProvider;
    @MockBean JwtService jwtService; // required to wire the secured filter chain

    private static final UUID DISPUTE_ID = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.randomUUID();

    private DisputeModel resolvedDisputeWithOneRuling() {
        Ruling ruling = new Ruling(1, RulingCategory.FULFILLED, "Work matches spec",
                RulingDecidedBy.ARBITRATOR, Instant.parse("2026-07-01T10:00:00Z"));
        return DisputeModel.open(TASK_ID, CLIENT_ID, RejectReason.A_MISMATCH, "corr-1")
                .startArbitrating()
                .recordRuling(ruling)
                .resolve();
    }

    @Test
    void acceptRuling_anonymous_401() throws Exception {
        mvc.perform(post("/api/disputes/{id}/accept-ruling", DISPUTE_ID).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void acceptRuling_delegates() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(CLIENT_ID);
        when(disputeReadAppService.getOutcomeByDispute(eq(DISPUTE_ID), eq(CLIENT_ID)))
                .thenReturn(resolvedDisputeWithOneRuling());

        mvc.perform(post("/api/disputes/{id}/accept-ruling", DISPUTE_ID).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.disputeId").exists())
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID.toString()));

        verify(disputeAppService).acceptRuling(eq(DISPUTE_ID), any());
    }

    @Test
    void appeal_anonymous_401() throws Exception {
        mvc.perform(post("/api/disputes/{id}/appeal", DISPUTE_ID).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void appeal_delegates() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(CLIENT_ID);
        when(disputeReadAppService.getOutcomeByDispute(eq(DISPUTE_ID), eq(CLIENT_ID)))
                .thenReturn(resolvedDisputeWithOneRuling());

        mvc.perform(post("/api/disputes/{id}/appeal", DISPUTE_ID).with(csrf()))
                .andExpect(status().isOk());

        verify(disputeAppService).appeal(eq(DISPUTE_ID), any());
    }

    @Test
    void mine_anonymous_401() throws Exception {
        mvc.perform(get("/api/disputes/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void mine_returnsRows() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(CLIENT_ID);
        when(disputeReadAppService.myDisputes(eq(CLIENT_ID))).thenReturn(List.of(
                new DisputeMineRow(DISPUTE_ID, TASK_ID, "t", "RULED", "FULFILLED", Instant.now())));

        mvc.perform(get("/api/disputes/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].disputeId").value(DISPUTE_ID.toString()))
                .andExpect(jsonPath("$.data[0].taskId").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.data[0].taskTitle").value("t"))
                .andExpect(jsonPath("$.data[0].status").value("RULED"))
                .andExpect(jsonPath("$.data[0].proposedCategory").value("FULFILLED"));
    }
}
