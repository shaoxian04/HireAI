package com.hireai.controller.config;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.OAuthAppService;
import com.hireai.application.biz.auth.OAuthUserInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * On a successful Google handshake, resolves/links/creates the local account, mints our JWT, and
 * redirects the browser to the frontend callback with the token in a URL fragment (not sent in
 * Referer or server logs). Any failure redirects to the frontend login with ?error=oauth.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "hireai.auth.oauth2.enabled", havingValue = "true")
@Slf4j
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuthAppService oauthAppService;
    private final String successRedirectUrl;
    private final String failureRedirectUrl;

    public OAuth2AuthenticationSuccessHandler(
            OAuthAppService oauthAppService,
            @Value("${hireai.auth.oauth2.success-redirect-url}") String successRedirectUrl,
            @Value("${hireai.auth.oauth2.failure-redirect-url}") String failureRedirectUrl) {
        this.oauthAppService = oauthAppService;
        this.successRedirectUrl = successRedirectUrl;
        this.failureRedirectUrl = failureRedirectUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            OAuth2User user = token.getPrincipal();
            OAuthUserInfo info = new OAuthUserInfo(
                    token.getAuthorizedClientRegistrationId(),
                    user.getAttribute("sub"),
                    user.getAttribute("email"),
                    isVerified(user.getAttribute("email_verified")),
                    user.getAttribute("name"));

            AuthResult result = oauthAppService.loginWithOAuth(info);
            String redirect = successRedirectUrl + "#token=" + result.token();
            HttpSession session = request.getSession(false);
            if (session != null) {
                Object nonce = session.getAttribute(NonceCarryingAuthorizationRequestResolver.NONCE_SESSION_ATTR);
                if (nonce != null) {
                    session.removeAttribute(NonceCarryingAuthorizationRequestResolver.NONCE_SESSION_ATTR);
                    redirect = redirect + "&nonce=" + URLEncoder.encode(nonce.toString(), StandardCharsets.UTF_8);
                }
            }
            response.sendRedirect(redirect);
        } catch (Exception ex) {
            log.warn("OAuth login failed: {}", ex.getMessage());
            response.sendRedirect(failureRedirectUrl + "?error=oauth");
        }
    }

    /** Google returns a boolean; tolerate the string form defensively. */
    private boolean isVerified(Object claim) {
        return Boolean.TRUE.equals(claim) || "true".equals(String.valueOf(claim));
    }
}
