package com.hireai.controller.biz.task;

import com.hireai.application.biz.adjudication.validation.ValidationReadAppService;
import com.hireai.application.biz.task.MatchPreviewAppService;
import com.hireai.application.biz.task.MatchPreviewAppService.AgentOption;
import com.hireai.application.biz.task.MatchPreviewAppService.MatchPreview;
import com.hireai.application.biz.task.SubmitContext;
import com.hireai.application.biz.task.SubmitOrchestrationAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.controller.config.CurrentApiKeyProvider;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
    @MockBean SubmitOrchestrationAppService submitOrchestrationAppService;
    @MockBean CurrentUserProvider currentUserProvider;
    @MockBean CurrentApiKeyProvider currentApiKeyProvider;
    @MockBean TaskReviewAppService taskReviewAppService;
    @MockBean MatchPreviewAppService matchPreviewAppService;
    @MockBean ValidationReadAppService validationReadAppService;

    @BeforeEach
    void noApiKeyByDefault() {
        when(currentApiKeyProvider.current()).thenReturn(Optional.empty());
    }

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
        when(submitOrchestrationAppService.submitDirect(any(), any())).thenReturn(taskId);
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
        when(submitOrchestrationAppService.submitDirect(any(), any()))
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
        when(submitOrchestrationAppService.submitDirect(any(), any()))
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

    @Test
    void submitWithIdempotencyKeyPassesItThroughToOrchestration() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(submitOrchestrationAppService.submitRouted(any(), any())).thenReturn(taskId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId)))
                .thenReturn(submittedTask(taskId, clientId));

        mockMvc.perform(post("/api/tasks").header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"T","description":"d","category":"summarisation","budget":"20.00",
                                 "outputSpec":{"format":"JSON","schema":"{}","acceptanceCriteria":"ok"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        ArgumentCaptor<SubmitContext> ctxCaptor = ArgumentCaptor.forClass(SubmitContext.class);
        verify(submitOrchestrationAppService).submitRouted(ctxCaptor.capture(), any());
        assertThat(ctxCaptor.getValue().idempotencyKey()).isEqualTo("abc-123");
    }

    // ---- POST /api/tasks/{id}/accept and /reject ----

    private TaskModel resolvedTask(UUID clientId, boolean accepted) {
        TaskModel t = TaskModel.submit(clientId, "title", "desc", Money.of("20.00"),
                        new OutputSpec(OutputFormat.TEXT, null, null), "summarisation")
                .assignAndQueue(UUID.randomUUID()).markExecuting();
        t = t.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), t.id(), "COMPLETED", "{}", null, Instant.now()))
                .passValidation(); // validation gate must pass before client review
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
        when(taskReviewAppService.reject(eq(taskId), eq(clientId), eq(RejectReason.A_MISMATCH), eq("not what I asked"))).thenReturn(taskId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId)))
                .thenReturn(resolvedTask(clientId, false));

        mockMvc.perform(post("/api/tasks/{id}/reject", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCategory\":\"A_MISMATCH\",\"reason\":\"not what I asked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("REJECTED"))
                .andExpect(jsonPath("$.data.refundAmount").value(20.00))
                .andExpect(jsonPath("$.data.rejectionReason").value("not what I asked"));
    }

    @Test
    void rejectWithoutBodyReturns400() throws Exception {
        // reasonCategory is now @NotNull — missing body → 400 VALIDATION_ERROR.
        // OLD: no body was optional (required=false). NEW: body with reasonCategory is mandatory.
        UUID clientId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);

        mockMvc.perform(post("/api/tasks/{id}/reject", UUID.randomUUID()))
                .andExpect(status().isBadRequest());
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
                        .content("{\"reasonCategory\":\"A_MISMATCH\",\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---- GET /api/tasks/match-preview ----

    @Test
    void matchPreviewReturns200WithBothLists() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(matchPreviewAppService.preview(eq("summarisation"), any()))
                .thenReturn(new MatchPreview(
                        List.of(new AgentOption(agentId, versionId, "Alpha", "tag", "logo",
                                new BigDecimal("12.00"), new BigDecimal("80.00"), true, "JSON",
                                List.of("summarisation"))),
                        List.of(new AgentOption(UUID.randomUUID(), UUID.randomUUID(), "Pricey", null, null,
                                new BigDecimal("40.00"), new BigDecimal("90.00"), false, "JSON",
                                List.of("summarisation")))));

        mockMvc.perform(get("/api/tasks/match-preview")
                        .param("category", "summarisation").param("budget", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shortlist[0].agentName").value("Alpha"))
                .andExpect(jsonPath("$.data.shortlist[0].price").value(12.00))
                .andExpect(jsonPath("$.data.shortlist[0].availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.nearMisses[0].agentName").value("Pricey"))
                .andExpect(jsonPath("$.data.nearMisses[0].availability").value("BUSY"));
    }

    @Test
    void matchPreviewBlankCategoryReturns400() throws Exception {
        mockMvc.perform(get("/api/tasks/match-preview").param("category", "").param("budget", "30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void matchPreviewNonPositiveBudgetReturns400() throws Exception {
        mockMvc.perform(get("/api/tasks/match-preview").param("category", "summarisation").param("budget", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---- GET /api/tasks/{id}/validation ----

    @Test
    void validationReturns200WithFailedChecksForOwner() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId))).thenReturn(null);
        when(validationReadAppService.latestForTask(eq(taskId)))
                .thenReturn(Optional.of(ValidationReportModel.of(taskId, 1,
                        List.of(new CheckResult("format", false, "expected FILE, got none")))));

        mockMvc.perform(get("/api/tasks/{id}/validation", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("FAIL"))
                .andExpect(jsonPath("$.data.checks[0].rule").value("format"))
                .andExpect(jsonPath("$.data.checks[0].passed").value(false))
                .andExpect(jsonPath("$.data.checks[0].detail").value("expected FILE, got none"));
    }

    @Test
    void validationReturns404WhenNoReport() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId))).thenReturn(null);
        when(validationReadAppService.latestForTask(eq(taskId))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/tasks/{id}/validation", taskId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void validationReturns404ForNonOwner() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId)))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));

        mockMvc.perform(get("/api/tasks/{id}/validation", taskId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
