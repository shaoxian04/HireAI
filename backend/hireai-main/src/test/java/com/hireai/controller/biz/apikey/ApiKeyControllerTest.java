package com.hireai.controller.biz.apikey;

import com.hireai.application.biz.apikey.ApiKeyManagementAppService;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.ApiKeyStatus;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiKeyController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
class ApiKeyControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ApiKeyManagementAppService managementAppService;
    @MockBean CurrentUserProvider currentUserProvider;

    private ApiKeyModel key(UUID id, UUID owner) {
        return ApiKeyModel.rehydrate(id, owner, "hash", "hk_live_a1b2c3", "ci-bot",
                new java.math.BigDecimal("100.00"), null, ApiKeyStatus.ACTIVE, null,
                Instant.parse("2026-07-15T10:00:00Z"), null);
    }

    @Test
    void createReturnsRawKeyOnce() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(owner);
        when(managementAppService.create(eq(owner), eq("ci-bot"), any(), isNull()))
                .thenReturn(new IssuedApiKey(key(keyId, owner), "hk_live_RAWSECRET"));

        mockMvc.perform(post("/api/keys").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ci-bot\",\"spendCap\":\"100.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawKey").value("hk_live_RAWSECRET"))
                .andExpect(jsonPath("$.data.displayPrefix").value("hk_live_a1b2c3"));
    }

    @Test
    void listOmitsRawKey() throws Exception {
        UUID owner = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(owner);
        when(managementAppService.list(owner)).thenReturn(List.of(key(UUID.randomUUID(), owner)));

        mockMvc.perform(get("/api/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].displayPrefix").value("hk_live_a1b2c3"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].rawKey").doesNotExist());
    }

    @Test
    void revokeReturnsUpdatedKey() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(owner);
        ApiKeyModel revoked = key(keyId, owner).revoke(Instant.parse("2026-07-15T11:00:00Z"));
        when(managementAppService.revoke(keyId, owner)).thenReturn(revoked);

        mockMvc.perform(post("/api/keys/{id}/revoke", keyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
    }
}
