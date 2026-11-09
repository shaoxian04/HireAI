package com.hireai.controller.biz.agentcallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.task.callback.AgentCallbackAppService;
import com.hireai.utility.exception.DispatchTokenInvalidException;
import com.hireai.controller.biz.agentcallback.dto.AgentResultCallbackRequest;
import com.hireai.controller.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller mapping only (authentication outcomes are asserted against the full app in
 * {@code SignedCallbackIntegrationTest}): the raw body is authenticated before it is parsed.
 */
@WebMvcTest(AgentCallbackController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
class AgentCallbackControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AgentCallbackAppService agentCallbackAppService;

    private String body() throws Exception {
        return objectMapper.writeValueAsString(
                new AgentResultCallbackRequest("COMPLETED", "{\"k\":\"v\"}", "https://x/y", "done"));
    }

    @Test
    void authenticatesTheRawBytesThenRecordsTheParsedResult() throws Exception {
        UUID taskId = UUID.randomUUID();
        String body = body();

        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", taskId)
                        .header("Authorization", "Bearer good-token")
                        .header("X-HireAI-Signature", "t=1,v1=ab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        InOrder order = inOrder(agentCallbackAppService);
        order.verify(agentCallbackAppService).authenticate(eq(taskId), eq("good-token"), eq("t=1,v1=ab"),
                eq(body.getBytes(StandardCharsets.UTF_8)));
        order.verify(agentCallbackAppService).recordResult(eq(taskId), eq("good-token"), any());
    }

    @Test
    void anUnauthenticatedMalformedBodyIsRejectedBeforeParsing() throws Exception {
        doThrow(new DispatchTokenInvalidException("bad signature"))
                .when(agentCallbackAppService).authenticate(any(), any(), any(), any());

        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", UUID.randomUUID())
                        .header("Authorization", "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isUnauthorized());
        verify(agentCallbackAppService, never()).recordResult(any(), any(), any());
    }

    @Test
    void anAuthenticatedMalformedBodyIs400() throws Exception {
        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", UUID.randomUUID())
                        .header("Authorization", "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
        verify(agentCallbackAppService, never()).recordResult(any(), any(), any());
    }

    @Test
    void anAuthenticatedBodyMissingRequiredFieldsIs400() throws Exception {
        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", UUID.randomUUID())
                        .header("Authorization", "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentStatus\":\"COMPLETED\"}"))
                .andExpect(status().isBadRequest());
        verify(agentCallbackAppService, never()).recordResult(any(), any(), any());
    }

    @Test
    void returns401WhenTokenInvalid() throws Exception {
        doThrow(new DispatchTokenInvalidException("bad token"))
                .when(agentCallbackAppService).recordResult(any(), any(), any());

        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", UUID.randomUUID())
                        .header("Authorization", "Bearer bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns401WhenAuthorizationHeaderMissing() throws Exception {
        mockMvc.perform(post("/api/agent-callbacks/{taskId}/result", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnauthorized());
        Mockito.verifyNoInteractions(agentCallbackAppService);
    }
}
