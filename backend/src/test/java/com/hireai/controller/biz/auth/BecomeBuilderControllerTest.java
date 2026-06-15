package com.hireai.controller.biz.auth;

import com.hireai.application.biz.auth.AuthAppService;
import com.hireai.application.biz.auth.AuthResult;
import com.hireai.controller.config.DevCurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, DevCurrentUserProvider.class})
class BecomeBuilderControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthAppService authAppService;

    @Test
    void upgradesCurrentUserToBuilder() throws Exception {
        when(authAppService.becomeBuilder(eq(DevCurrentUserProvider.DEV_USER_ID)))
                .thenReturn(new AuthResult("expanded.jwt", DevCurrentUserProvider.DEV_USER_ID,
                        List.of("BUILDER", "CLIENT")));

        mvc.perform(post("/api/auth/become-builder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptTerms\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("expanded.jwt"))
                .andExpect(jsonPath("$.data.roles", org.hamcrest.Matchers.containsInAnyOrder("BUILDER", "CLIENT")));
    }

    @Test
    void rejectsWhenTermsNotAccepted() throws Exception {
        mvc.perform(post("/api/auth/become-builder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptTerms\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
