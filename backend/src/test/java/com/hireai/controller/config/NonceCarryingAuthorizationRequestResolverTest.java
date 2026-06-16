package com.hireai.controller.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;

class NonceCarryingAuthorizationRequestResolverTest {

    private static final String AUTH_PATH = "/oauth2/authorization/google";

    private final InMemoryClientRegistrationRepository repo = new InMemoryClientRegistrationRepository(
            ClientRegistration.withRegistrationId("google")
                    .clientId("client-id")
                    .clientSecret("client-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://oauth2.googleapis.com/token")
                    .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                    .userNameAttributeName("sub")
                    .scope("openid", "email", "profile")
                    .build()
    );

    private final NonceCarryingAuthorizationRequestResolver resolver =
            new NonceCarryingAuthorizationRequestResolver(repo);

    @Test
    void stashesValidNonceInSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(AUTH_PATH);
        request.setServletPath(AUTH_PATH);
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.addParameter("cb_nonce", "n0nce-AB_cd");

        OAuth2AuthorizationRequest resolved = resolver.resolve(request);

        assertThat(resolved).isNotNull();
        assertThat(request.getSession().getAttribute(NonceCarryingAuthorizationRequestResolver.NONCE_SESSION_ATTR))
                .isEqualTo("n0nce-AB_cd");
    }

    @Test
    void rejectsNonceWithIllegalCharacters() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(AUTH_PATH);
        request.setServletPath(AUTH_PATH);
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.addParameter("cb_nonce", "bad nonce!");

        resolver.resolve(request);

        assertThat(request.getSession(false) == null
                || request.getSession(false).getAttribute(NonceCarryingAuthorizationRequestResolver.NONCE_SESSION_ATTR) == null)
                .isTrue();
    }
}
