package com.hireai.controller.biz.task;

import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.shared.exception.DomainException;
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
 * Web-slice test for the task result endpoint. Identity comes from {@link CurrentUserProvider}
 * (mocked here — the WebMvcTest slice does not load the test-profile DevCurrentUserProvider). The
 * happy path returns 200 + the WebResult envelope; a NOT_FOUND DomainException maps to HTTP 404 via
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
}
