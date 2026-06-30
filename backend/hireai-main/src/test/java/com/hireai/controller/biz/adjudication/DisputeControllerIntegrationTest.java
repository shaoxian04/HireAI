package com.hireai.controller.biz.adjudication;

import com.hireai.application.biz.adjudication.dispute.DisputeReadAppService;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test for the dispute transparency read endpoint.
 * Uses the test profile's permissive SecurityFilterChain (CSRF off, permitAll) and a mocked
 * CurrentUserProvider, mirroring the pattern used by TaskControllerTest and siblings.
 */
@WebMvcTest(DisputeController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
class DisputeControllerIntegrationTest {

    @Autowired MockMvc mockMvc;

    @MockBean DisputeReadAppService disputeReadAppService;
    @MockBean CurrentUserProvider currentUserProvider;

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
    void returns200WithOutcomeAndRulingForParticipant() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(CLIENT_ID);
        when(disputeReadAppService.getOutcomeForUser(eq(TASK_ID), eq(CLIENT_ID)))
                .thenReturn(resolvedDisputeWithOneRuling());

        mockMvc.perform(get("/api/disputes/by-task/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.effectiveCategory").value("FULFILLED"))
                .andExpect(jsonPath("$.data.rulings").isArray())
                .andExpect(jsonPath("$.data.rulings.length()").value(1))
                .andExpect(jsonPath("$.data.rulings[0].tier").value(1))
                .andExpect(jsonPath("$.data.rulings[0].decidedBy").value("ARBITRATOR"))
                .andExpect(jsonPath("$.data.rulings[0].category").value("FULFILLED"))
                .andExpect(jsonPath("$.data.rulings[0].rationale").value("Work matches spec"));
    }

    @Test
    void returns404ForUnknownTaskOrNonParticipant() throws Exception {
        UUID unknownTaskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(CLIENT_ID);
        when(disputeReadAppService.getOutcomeForUser(eq(unknownTaskId), eq(CLIENT_ID)))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Dispute not found for task: " + unknownTaskId));

        mockMvc.perform(get("/api/disputes/by-task/{taskId}", unknownTaskId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void noDisputeOnTaskReturns404() throws Exception {
        UUID taskWithNoDispute = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(CLIENT_ID);
        when(disputeReadAppService.getOutcomeForUser(eq(taskWithNoDispute), eq(CLIENT_ID)))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Dispute not found for task: " + taskWithNoDispute));

        mockMvc.perform(get("/api/disputes/by-task/{taskId}", taskWithNoDispute))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
