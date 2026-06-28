package com.hireai.controller.biz.auth;

import com.hireai.application.biz.identity.AuthAppService;
import com.hireai.application.biz.identity.AuthResult;
import com.hireai.utility.exception.EmailAlreadyRegisteredException;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, DevCurrentUserProvider.class})
@ActiveProfiles("test")
class AuthRegisterControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthAppService authAppService;

    @Test
    void registersAndReturnsToken() throws Exception {
        when(authAppService.register(any())).thenReturn(
                new AuthResult("jwt", UUID.randomUUID(), List.of("CLIENT")));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@hireai.local\",\"password\":\"Sup3rSecret!\",\"displayName\":\"N\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").value("jwt"))
                .andExpect(jsonPath("$.data.roles[0]").value("CLIENT"));
    }

    @Test
    void rejectsShortPasswordWith400() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@hireai.local\",\"password\":\"short\",\"displayName\":\"N\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void returns409OnDuplicateEmail() throws Exception {
        when(authAppService.register(any())).thenThrow(new EmailAlreadyRegisteredException());

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"taken@hireai.local\",\"password\":\"Sup3rSecret!\",\"displayName\":\"N\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }
}
