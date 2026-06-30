package com.hireai.controller.biz.adjudication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.controller.biz.adjudication.dto.ArbitrationRulingRequest;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ArbitrationCallbackController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
@TestPropertySource(properties = "hireai.arbitration.callback-secret=test-secret")
class ArbitrationCallbackControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean DisputeAppService disputeAppService;

    private String validBody() throws Exception {
        return objectMapper.writeValueAsString(new ArbitrationRulingRequest("FULFILLED", "looks good"));
    }

    @Test
    void returns200AndDelegatesOnValidSecretAndBody() throws Exception {
        UUID disputeId = UUID.randomUUID();

        mockMvc.perform(post("/api/arbitration-callbacks/{disputeId}/ruling", disputeId)
                        .header("Authorization", "Bearer test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk());

        verify(disputeAppService).applyRuling(
                eq(disputeId),
                eq(new RulingInfo(RulingCategory.FULFILLED, "looks good")));
    }

    @Test
    void returns401WhenAuthorizationHeaderMissing() throws Exception {
        UUID disputeId = UUID.randomUUID();

        mockMvc.perform(post("/api/arbitration-callbacks/{disputeId}/ruling", disputeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(disputeAppService);
    }

    @Test
    void returns401WhenWrongSecret() throws Exception {
        UUID disputeId = UUID.randomUUID();

        mockMvc.perform(post("/api/arbitration-callbacks/{disputeId}/ruling", disputeId)
                        .header("Authorization", "Bearer wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(disputeAppService);
    }

    @Test
    void returns401WhenBearerTokenIsBlank() throws Exception {
        UUID disputeId = UUID.randomUUID();

        mockMvc.perform(post("/api/arbitration-callbacks/{disputeId}/ruling", disputeId)
                        .header("Authorization", "Bearer ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(disputeAppService);
    }

    @Test
    void returns400WhenCategoryIsUnknown() throws Exception {
        UUID disputeId = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(new ArbitrationRulingRequest("BOGUS", "rationale"));

        mockMvc.perform(post("/api/arbitration-callbacks/{disputeId}/ruling", disputeId)
                        .header("Authorization", "Bearer test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(disputeAppService);
    }

    @Test
    void returns400WhenCategoryIsBlank() throws Exception {
        UUID disputeId = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(new ArbitrationRulingRequest("", "rationale"));

        mockMvc.perform(post("/api/arbitration-callbacks/{disputeId}/ruling", disputeId)
                        .header("Authorization", "Bearer test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(disputeAppService);
    }

    @Test
    void returns404WhenDisputeNotFound() throws Exception {
        UUID disputeId = UUID.randomUUID();
        doThrow(new DomainException(ResultCode.NOT_FOUND, "Dispute not found"))
                .when(disputeAppService).applyRuling(eq(disputeId), any());

        mockMvc.perform(post("/api/arbitration-callbacks/{disputeId}/ruling", disputeId)
                        .header("Authorization", "Bearer test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isNotFound());

        verify(disputeAppService).applyRuling(eq(disputeId), any());
    }
}
