package com.hireai.controller.config;

import com.hireai.application.biz.apikey.ApiKeyAuthService;
import com.hireai.application.biz.apikey.ApiKeyPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Reads an API key from {@code Authorization: ApiKey <raw>} or {@code X-API-Key: <raw>}, resolves it
 * via {@link ApiKeyAuthService}, and on success sets a {@link UsernamePasswordAuthenticationToken}
 * whose principal is the user id (UUID) — identical to {@link JwtAuthenticationFilter}, so every
 * downstream ownership check works unchanged (Invariant #5) — with a single {@code ROLE_API_CLIENT}
 * authority and {@link ApiKeyContext} as details. Never overrides an already-set authentication (a
 * JWT filter may have run first) and never writes a response. Invalid/absent key → context stays
 * empty → the chain returns 401 on protected routes.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_SCHEME = "ApiKey ";
    private static final String X_API_KEY = "X-API-Key";
    private static final String ROLE_API_CLIENT = "ROLE_API_CLIENT";

    private final ApiKeyAuthService authService;

    public ApiKeyAuthenticationFilter(ApiKeyAuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String rawKey = extractKey(request);
            if (rawKey != null) {
                Optional<ApiKeyPrincipal> principal = authService.authenticate(rawKey);
                principal.ifPresent(p -> {
                    var auth = new UsernamePasswordAuthenticationToken(
                            p.userId(), null, List.of(new SimpleGrantedAuthority(ROLE_API_CLIENT)));
                    auth.setDetails(new ApiKeyContext(p.keyId(), p.spendCap(), p.dailySpendCap()));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(API_KEY_SCHEME)) {
            return header.substring(API_KEY_SCHEME.length()).trim();
        }
        String xApiKey = request.getHeader(X_API_KEY);
        return (xApiKey != null && !xApiKey.isBlank()) ? xApiKey.trim() : null;
    }
}
