package com.hireai.controller.biz.catalogue;

import com.hireai.application.biz.offering.catalogue.CatalogueReadAppService;
import com.hireai.application.port.query.CatalogueQueryPort.AgentCardRow;
import com.hireai.application.port.query.CatalogueQueryPort.AgentProfileRow;
import com.hireai.application.port.query.CatalogueQueryPort.CategoryCountRow;
import com.hireai.application.port.query.CatalogueQueryPort.ReviewRow;
import com.hireai.utility.result.ResultCode;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import com.hireai.application.biz.task.OutputSpecJsonMapper;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CatalogueController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
class CatalogueControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean CatalogueReadAppService catalogueReadAppService;
    @MockBean(name = "outputSpecJsonMapper") OutputSpecJsonMapper outputSpecJsonMapper;

    private AgentCardRow card(String name) {
        return new AgentCardRow(UUID.randomUUID(), name, "alice", new BigDecimal("60.00"),
                "Fast summaries", null, null, false, List.of("summarisation"),
                new BigDecimal("10.00"), 60, new BigDecimal("4.50"), 3, 7, Instant.now());
    }

    @Test
    void listPassesFiltersAndReturnsCards() throws Exception {
        when(catalogueReadAppService.search(eq("sum"), eq("summarisation"), eq("rating"), eq(0), eq(20)))
                .thenReturn(List.of(card("Summariser Bot")));

        mockMvc.perform(get("/api/catalogue/agents")
                        .param("q", "sum").param("category", "summarisation")
                        .param("sort", "rating").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("Summariser Bot"))
                .andExpect(jsonPath("$.data[0].builderName").value("alice"))
                .andExpect(jsonPath("$.data[0].ratingAvg").value(4.5))
                .andExpect(jsonPath("$.data[0].requestCount").value(7));
    }

    @Test
    void profileReturns404WhenNotListed() throws Exception {
        UUID id = UUID.randomUUID();
        when(catalogueReadAppService.getProfile(id))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + id));

        mockMvc.perform(get("/api/catalogue/agents/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void categoriesEndpointReturnsCounts() throws Exception {
        when(catalogueReadAppService.categories()).thenReturn(
                List.of(new CategoryCountRow("summarisation", 2)));

        mockMvc.perform(get("/api/catalogue/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].category").value("summarisation"))
                .andExpect(jsonPath("$.data[0].agentCount").value(2));
    }

    @Test
    void listUsesDefaultParams() throws Exception {
        when(catalogueReadAppService.search(eq(""), eq(""), eq("hot"), eq(0), eq(20)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/catalogue/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(catalogueReadAppService).search("", "", "hot", 0, 20);
    }

    @Test
    void nonUuidAgentPathReturns400() throws Exception {
        mockMvc.perform(get("/api/catalogue/agents/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void profileMapsOutputSpecAndReviews() throws Exception {
        UUID agentId = UUID.randomUUID();
        AgentCardRow cardRow = new AgentCardRow(agentId, "Summariser Bot", "alice",
                new BigDecimal("60.00"), "Fast summaries", null, null, false,
                List.of("summarisation"), new BigDecimal("10.00"), 60,
                new BigDecimal("4.50"), 3, 7, Instant.now());
        AgentProfileRow profileRow = new AgentProfileRow(
                cardRow, "A detailed description", "sample output here",
                List.of(), "{\"format\":\"JSON\",\"schema\":\"{}\",\"acceptanceCriteria\":\"valid JSON\"}",
                6, 45.0);

        ReviewRow review = new ReviewRow(UUID.randomUUID(), 5, "Great agent!", null, "client",
                Instant.now());

        when(catalogueReadAppService.getProfile(agentId)).thenReturn(profileRow);
        when(catalogueReadAppService.reviews(agentId)).thenReturn(List.of(review));
        when(outputSpecJsonMapper.fromJson(anyString()))
                .thenReturn(new OutputSpec(OutputFormat.JSON, "{}", "valid JSON"));

        mockMvc.perform(get("/api/catalogue/agents/{id}", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outputSpec.format").value("JSON"))
                .andExpect(jsonPath("$.data.stats.requestCount").value(7))
                .andExpect(jsonPath("$.data.stats.completedCount").value(6))
                .andExpect(jsonPath("$.data.stats.successRate").value(6.0 / 7))
                .andExpect(jsonPath("$.data.reviews[0].author").value("client"));
    }
}
