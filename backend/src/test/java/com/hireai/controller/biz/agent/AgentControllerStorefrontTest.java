package com.hireai.controller.biz.agent;

import com.hireai.application.biz.agent.AgentReadAppService;
import com.hireai.application.biz.agent.AgentStorefrontAppService;
import com.hireai.application.biz.agent.AgentWriteAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.model.AgentVersionModel;
import com.hireai.domain.biz.agent.model.Pricing;
import com.hireai.domain.biz.review.model.ReviewModel;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test for the storefront management endpoints added to AgentController.
 * The test profile uses a permissive SecurityFilterChain (CSRF off, permitAll), so
 * multipart POST requests succeed without a CSRF token.
 */
@WebMvcTest(AgentController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
class AgentControllerStorefrontTest {

    @Autowired MockMvc mockMvc;

    @MockBean AgentWriteAppService writeAppService;
    @MockBean AgentReadAppService readAppService;
    @MockBean AgentStorefrontAppService storefrontAppService;
    @MockBean CurrentUserProvider currentUserProvider;

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    private AgentProfileModel listedProfile() {
        return AgentProfileModel.createDefault(AGENT_ID)
                .updateContent("Fast summaries", "Great agent", "sample", true);
    }

    private ReviewModel reviewWithResponse() {
        return new ReviewModel(UUID.randomUUID(), null, UUID.randomUUID(), AGENT_ID,
                5, "Excellent!", "Thank you!", true, Instant.now());
    }

    @Test
    void putProfile_happyPath_returns200WithListedFlag() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(storefrontAppService.updateProfile(eq(AGENT_ID), eq(OWNER_ID), any()))
                .thenReturn(listedProfile());

        mockMvc.perform(put("/api/agents/{id}/profile", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tagline": "Fast summaries",
                                  "description": "Great agent",
                                  "sampleOutput": "sample",
                                  "isListed": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tagline").value("Fast summaries"))
                .andExpect(jsonPath("$.data.listed").value(true));
    }

    @Test
    void getProfile_foreignOwner_notFoundReturns404() throws Exception {
        UUID foreignId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(foreignId);
        when(storefrontAppService.getProfile(eq(AGENT_ID), eq(foreignId)))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + AGENT_ID));

        mockMvc.perform(get("/api/agents/{id}/profile", AGENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void postMedia_multipartUploadHappyPath_returns200() throws Exception {
        byte[] imageBytes = new byte[512];
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", imageBytes);

        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(storefrontAppService.uploadMedia(eq(AGENT_ID), eq(OWNER_ID), eq("logo"),
                eq("image/png"), eq(512L), any()))
                .thenReturn(listedProfile().withLogo("https://storage.example.com/logo.png"));

        mockMvc.perform(multipart("/api/agents/{id}/media", AGENT_ID)
                        .file(file)
                        .param("kind", "logo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void putReviewResponse_happyPath_returns200WithBuilderResponse() throws Exception {
        ReviewModel reviewed = reviewWithResponse();
        UUID reviewId = reviewed.id();
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(storefrontAppService.respondToReview(eq(AGENT_ID), eq(OWNER_ID), eq(reviewId), eq("Thank you!")))
                .thenReturn(reviewed);

        mockMvc.perform(put("/api/agents/{agentId}/reviews/{reviewId}/response", AGENT_ID, reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"response": "Thank you!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.builderResponse").value("Thank you!"));
    }

    @Test
    void postMedia_oversizeUpload_returns413WithValidationErrorCode() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", new byte[512]);
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(storefrontAppService.uploadMedia(any(), any(), any(), any(), anyLong(), any()))
                .thenThrow(new MaxUploadSizeExceededException(2097152));

        mockMvc.perform(multipart("/api/agents/{id}/media", AGENT_ID)
                        .file(file)
                        .param("kind", "logo"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("2 MB")));
    }

    @Test
    void putReviewResponse_blankResponse_returns400() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);

        mockMvc.perform(put("/api/agents/{agentId}/reviews/{reviewId}/response",
                        AGENT_ID, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"response": "   "}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ---- PUT /{agentId}/pricing tests ----

    private AgentModel updatedPricingModel(BigDecimal price, int maxExec, List<String> categories) {
        AgentVersionModel version = new AgentVersionModel(
                UUID.randomUUID(), AGENT_ID, 1,
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                categories, "https://agent.example.com/hook", maxExec,
                Pricing.of(price), Instant.now());
        return new AgentModel(AGENT_ID, OWNER_ID, "Test Agent",
                AgentStatus.ACTIVE, version.id(), new BigDecimal("50.00"), version, Instant.now());
    }

    @Test
    void putPricing_happyPath_returns200WithUpdatedPrice() throws Exception {
        AgentModel updated = updatedPricingModel(new BigDecimal("99.50"), 120, List.of("translation"));
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(writeAppService.updatePricing(eq(AGENT_ID), eq(OWNER_ID), any()))
                .thenReturn(updated);

        mockMvc.perform(put("/api/agents/{id}/pricing", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "price": 99.50,
                                  "maxExecutionSeconds": 120,
                                  "capabilityCategories": ["translation"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.currentVersion.price").value(99.50));
    }

    @Test
    void putPricing_foreignOwner_returns404() throws Exception {
        UUID foreignId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(foreignId);
        when(writeAppService.updatePricing(eq(AGENT_ID), eq(foreignId), any()))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + AGENT_ID));

        mockMvc.perform(put("/api/agents/{id}/pricing", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "price": 10.00,
                                  "maxExecutionSeconds": 60,
                                  "capabilityCategories": ["summarisation"]
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void putPricing_emptyCategories_returns400() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);

        mockMvc.perform(put("/api/agents/{id}/pricing", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "price": 10.00,
                                  "maxExecutionSeconds": 60,
                                  "capabilityCategories": []
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
