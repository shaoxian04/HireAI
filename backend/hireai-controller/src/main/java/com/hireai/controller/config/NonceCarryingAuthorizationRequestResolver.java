package com.hireai.controller.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * Wraps Spring's default OAuth2 authorization request resolver and stashes a validated
 * {@code cb_nonce} query parameter into the HTTP session. The nonce is used by
 * {@link OAuth2AuthenticationSuccessHandler} to bind the callback to the browser that
 * initiated the OAuth flow, preventing login-CSRF / session-fixation attacks.
 *
 * <p>The nonce is stored server-side only (in the session) and never forwarded to Google.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "hireai.auth.oauth2.enabled", havingValue = "true")
public class NonceCarryingAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    public static final String NONCE_SESSION_ATTR = "hireai.oauth.cb_nonce";

    private static final String NONCE_PATTERN = "^[A-Za-z0-9_-]{1,128}$";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public NonceCarryingAuthorizationRequestResolver(ClientRegistrationRepository repo) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization");
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest resolved = delegate.resolve(request);
        stash(request, resolved);
        return resolved;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest resolved = delegate.resolve(request, clientRegistrationId);
        stash(request, resolved);
        return resolved;
    }

    private void stash(HttpServletRequest request, OAuth2AuthorizationRequest resolved) {
        if (resolved == null) {
            return;
        }
        String nonce = request.getParameter("cb_nonce");
        if (nonce != null && !nonce.isBlank() && nonce.matches(NONCE_PATTERN)) {
            HttpSession session = request.getSession();
            session.setAttribute(NONCE_SESSION_ATTR, nonce);
        }
    }
}
