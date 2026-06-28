package com.hireai.controller.biz.wallet;

import com.hireai.application.biz.wallet.BuilderEarningsReadAppService;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.AgentEarnings;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.Earnings;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.Payout;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web slice: envelope, field mapping, identity from CurrentUserProvider only. */
@WebMvcTest(BuilderEarningsController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
class BuilderEarningsControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean BuilderEarningsReadAppService earningsService;
    @MockBean CurrentUserProvider currentUserProvider;

    @Test
    void returnsEarningsEnvelope() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(userId);
        when(earningsService.earningsFor(userId)).thenReturn(new Earnings(
                new BigDecimal("27.20"), new BigDecimal("17.00"), 2,
                List.of(new AgentEarnings(agentId, "Summariser Bot",
                        new BigDecimal("27.20"), new BigDecimal("17.00"), 2)),
                List.of(new Payout(taskId, "Summarize the article", "Summariser Bot",
                        new BigDecimal("10.20"), Instant.parse("2026-06-07T04:28:39Z")))));

        mockMvc.perform(get("/api/builder/earnings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.lifetimeEarned").value(27.20))
                .andExpect(jsonPath("$.data.pendingIfAccepted").value(17.00))
                .andExpect(jsonPath("$.data.paidTaskCount").value(2))
                .andExpect(jsonPath("$.data.perAgent[0].agentName").value("Summariser Bot"))
                .andExpect(jsonPath("$.data.perAgent[0].earned").value(27.20))
                .andExpect(jsonPath("$.data.payouts[0].taskTitle").value("Summarize the article"))
                .andExpect(jsonPath("$.data.payouts[0].amount").value(10.20));
    }

    @Test
    void emptyEarningsStillSucceed() throws Exception {
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(userId);
        when(earningsService.earningsFor(userId)).thenReturn(new Earnings(
                new BigDecimal("0.00"), new BigDecimal("0.00"), 0, List.of(), List.of()));

        mockMvc.perform(get("/api/builder/earnings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidTaskCount").value(0))
                .andExpect(jsonPath("$.data.perAgent").isEmpty())
                .andExpect(jsonPath("$.data.payouts").isEmpty());
    }
}
