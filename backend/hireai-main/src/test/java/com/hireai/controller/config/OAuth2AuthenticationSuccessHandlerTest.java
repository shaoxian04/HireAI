package com.hireai.controller.config;

import com.hireai.application.biz.identity.AuthResult;
import com.hireai.application.biz.identity.OAuthAppService;
import com.hireai.utility.exception.OAuthAuthenticationException;
import com.hireai.application.biz.identity.OAuthUserInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuth2AuthenticationSuccessHandlerTest {

    private final OAuthAppService oauthAppService = mock(OAuthAppService.class);
    private final OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(
            oauthAppService, "http://localhost:3000/auth/callback", "http://localhost:3000/login");

    private OAuth2AuthenticationToken googleToken(Map<String, Object> attrs) {
        OAuth2User user = new DefaultOAuth2User(Set.of(), attrs, "sub");
        return new OAuth2AuthenticationToken(user, List.of(), "google");
    }

    @Test
    void redirectsToCallbackWithTokenFragmentOnSuccess() throws Exception {
        when(oauthAppService.loginWithOAuth(any(OAuthUserInfo.class)))
                .thenReturn(new AuthResult("jwt.abc", UUID.randomUUID(), List.of("CLIENT")));
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), res, googleToken(Map.of(
                "sub", "sub-123", "email", "ada@hireai.local", "email_verified", true, "name", "Ada")));

        assertThat(res.getRedirectedUrl()).isEqualTo("http://localhost:3000/auth/callback#token=jwt.abc");
    }

    @Test
    void passesProviderAndClaimsThrough() throws Exception {
        when(oauthAppService.loginWithOAuth(any(OAuthUserInfo.class)))
                .thenReturn(new AuthResult("jwt", UUID.randomUUID(), List.of("CLIENT")));

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), new MockHttpServletResponse(),
                googleToken(Map.of("sub", "sub-123", "email", "ada@hireai.local",
                        "email_verified", true, "name", "Ada")));

        ArgumentCaptor<OAuthUserInfo> captor = ArgumentCaptor.forClass(OAuthUserInfo.class);
        org.mockito.Mockito.verify(oauthAppService).loginWithOAuth(captor.capture());
        assertThat(captor.getValue().provider()).isEqualTo("google");
        assertThat(captor.getValue().subject()).isEqualTo("sub-123");
        assertThat(captor.getValue().emailVerified()).isTrue();
        assertThat(captor.getValue().displayName()).isEqualTo("Ada");
    }

    @Test
    void echoesNonceFromSessionInFragment() throws Exception {
        when(oauthAppService.loginWithOAuth(any(OAuthUserInfo.class)))
                .thenReturn(new AuthResult("jwt.abc", UUID.randomUUID(), List.of("CLIENT")));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession().setAttribute(NonceCarryingAuthorizationRequestResolver.NONCE_SESSION_ATTR, "abc123");
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, res, googleToken(Map.of(
                "sub", "sub-123", "email", "ada@hireai.local", "email_verified", true, "name", "Ada")));

        assertThat(res.getRedirectedUrl())
                .isEqualTo("http://localhost:3000/auth/callback#token=jwt.abc&nonce=abc123");
        // One-time use: the nonce is consumed from the session so it cannot be replayed.
        assertThat(req.getSession().getAttribute(NonceCarryingAuthorizationRequestResolver.NONCE_SESSION_ATTR))
                .isNull();
    }

    @Test
    void redirectsToFailureUrlOnException() throws Exception {
        when(oauthAppService.loginWithOAuth(any(OAuthUserInfo.class)))
                .thenThrow(new OAuthAuthenticationException("nope"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), res, googleToken(Map.of(
                "sub", "sub-123", "email", "ada@hireai.local", "email_verified", false, "name", "Ada")));

        assertThat(res.getRedirectedUrl()).isEqualTo("http://localhost:3000/login?error=oauth");
    }
}
