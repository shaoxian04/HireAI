package com.hireai.controller.biz.task;

import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test for the task HTTP surface. Identity comes from {@link CurrentUserProvider}
 * (mocked here — the WebMvcTest slice does not load the test-profile DevCurrentUserProvider). The
 * happy path returns 200 + the WebResult envelope; DomainExceptions map to HTTP status codes via
 * the global advice loaded by @WebMvcTest.
 */
@WebMvcTest(TaskController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
class TaskControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean TaskReadAppService taskReadAppService;
    @MockBean TaskWriteAppService taskWriteAppService;
    @MockBean CurrentUserProvider currentUserProvider;
    @MockBean DirectBookingAppService directBookingAppService;
    @MockBean TaskReviewAppService taskReviewAppService;

    @Test
    void returns200WithResultPayloadForOwningClient() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReadAppService.getResult(eq(taskId), eq(clientId)))
                .thenReturn(TaskResultModel.rehydrate(UUID.randomUUID(), taskId, "COMPLETED",
                        "{\"summary\":\"ok\"}", "https://x/y", Instant.parse("2026-06-06T10:15:30Z")));

        mockMvc.perform(get("/api/tasks/{taskId}/result", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.data.agentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.resultPayloadJson").value("{\"summary\":\"ok\"}"))
                .andExpect(jsonPath("$.data.resultUrl").value("https://x/y"));
    }

    @Test
    void returns404WhenNoResult() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReadAppService.getResult(eq(taskId), eq(clientId)))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "No result for task: " + taskId));

        mockMvc.perform(get("/api/tasks/{taskId}/result", taskId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- POST /api/tasks/direct ----

    private TaskModel submittedTask(UUID taskId, UUID clientId) {
        return TaskModel.submit(clientId, "Summarise report", "Please summarise",
                Money.of("20.00"), new OutputSpec(OutputFormat.JSON, "{}", "ok"), "summarisation");
    }

    @Test
    void bookDirectReturns200WithTaskDTO() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(directBookingAppService.book(any())).thenReturn(taskId);
        TaskModel task = submittedTask(taskId, clientId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId))).thenReturn(task);

        mockMvc.perform(post("/api/tasks/direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Summarise report",
                                  "description": "Please summarise the quarterly report",
                                  "budget": "20.00",
                                  "agentId": "%s"
                                }
                                """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));
    }

    @Test
    void bookDirectValidationErrorReturns400() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(directBookingAppService.book(any()))
                .thenThrow(new DomainException(ResultCode.VALIDATION_ERROR,
                        "Budget 5.00 is below the agent's price 20.00"));

        mockMvc.perform(post("/api/tasks/direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Summarise report",
                                  "description": "Please summarise",
                                  "budget": "5.00",
                                  "agentId": "%s"
                                }
                                """.formatted(agentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void bookDirectNotFoundReturns404() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(directBookingAppService.book(any()))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Agent not found"));

        mockMvc.perform(post("/api/tasks/direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Summarise report",
                                  "description": "Please summarise",
                                  "budget": "20.00",
                                  "agentId": "%s"
                                }
                                """.formatted(agentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void bookDirectMissingAgentIdReturns400() throws Exception {
        UUID clientId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);

        mockMvc.perform(post("/api/tasks/direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Summarise report",
                                  "description": "Please summarise",
                                  "budget": "20.00"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    // ---- POST /api/tasks/{id}/accept and /reject ----

    private TaskModel resolvedTask(UUID clientId, boolean accepted) {
        TaskModel t = TaskModel.submit(clientId, "title", "desc", Money.of("20.00"),
                        new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(UUID.randomUUID()).markExecuting();
        t = t.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), t.id(), "COMPLETED", "{}", null, Instant.now()));
        return accepted ? t.accept() : t.reject("not what I asked");
    }

    @Test
    void acceptReturnsResolvedTaskWithSettlementAmounts() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReviewAppService.accept(eq(taskId), eq(clientId))).thenReturn(taskId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId)))
                .thenReturn(resolvedTask(clientId, true));

        mockMvc.perform(post("/api/tasks/{id}/accept", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolution").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.payoutAmount").value(17.00))
                .andExpect(jsonPath("$.data.commissionAmount").value(3.00))
                .andExpect(jsonPath("$.data.refundAmount").doesNotExist());
    }

    @Test
    void rejectPassesReasonAndReturnsRefundAmount() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReviewAppService.reject(eq(taskId), eq(clientId), eq("not what I asked"))).thenReturn(taskId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId)))
                .thenReturn(resolvedTask(clientId, false));

        mockMvc.perform(post("/api/tasks/{id}/reject", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not what I asked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("REJECTED"))
                .andExpect(jsonPath("$.data.refundAmount").value(20.00))
                .andExpect(jsonPath("$.data.rejectionReason").value("not what I asked"));
    }

    @Test
    void rejectWithoutBodyIsAccepted() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReviewAppService.reject(eq(taskId), eq(clientId), eq(null))).thenReturn(taskId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId)))
                .thenReturn(resolvedTask(clientId, false));

        mockMvc.perform(post("/api/tasks/{id}/reject", taskId))
                .andExpect(status().isOk());
    }

    @Test
    void acceptOnNonReviewableStateMapsTo409() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReviewAppService.accept(eq(taskId), eq(clientId)))
                .thenThrow(new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                        "Illegal transition accept from RESOLVED"));

        mockMvc.perform(post("/api/tasks/{id}/accept", taskId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_RULE_VIOLATION"));
    }

    @Test
    void rejectReasonOver500CharsIs400() throws Exception {
        UUID clientId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);

        mockMvc.perform(post("/api/tasks/{id}/reject", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
