package com.hireai.controller.biz.agentcallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.agentcallback.AgentCallbackAppService;
import com.hireai.application.port.security.DispatchTokenInvalidException;
import com.hireai.controller.biz.agentcallback.dto.AgentResultCallbackRequest;
import com.hireai.controller.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentCallbackController.class)
@Import(SecurityConfig.class)
@WithMockUser
class AgentCallbackControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AgentCallbackAppService agentCallbackAppService;

    private String body() throws Exception {
        return objectMapper.writeValueAsString(
                new AgentResultCallbackRequest("COMPLETED", "{\"k\":\"v\"}", "https://x/y", "done"));
    }

    @Test
    void returns200AndDelegatesOnValidToken() throws Exception {
        UUID taskId = UUID.randomUUID();

        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", taskId)
                        .header("Authorization", "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk());

        verify(agentCallbackAppService).recordResult(eq(taskId), eq("good-token"), any());
    }

    @Test
    void returns401WhenTokenInvalid() throws Exception {
        UUID taskId = UUID.randomUUID();
        doThrow(new DispatchTokenInvalidException("bad token"))
                .when(agentCallbackAppService).recordResult(any(), any(), any());

        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", taskId)
                        .header("Authorization", "Bearer bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns401WhenAuthorizationHeaderMissing() throws Exception {
        UUID taskId = UUID.randomUUID();

        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnauthorized());
        Mockito.verifyNoInteractions(agentCallbackAppService);
    }
}
