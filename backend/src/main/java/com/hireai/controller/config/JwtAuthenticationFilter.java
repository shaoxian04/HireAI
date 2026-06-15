package com.hireai.controller.config;

import com.hireai.application.port.security.JwtInvalidException;
import com.hireai.application.port.security.JwtPrincipal;
import com.hireai.application.port.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads {@code Authorization: Bearer <jwt>}, verifies it via {@link JwtService}, and on success sets
 * a {@link UsernamePasswordAuthenticationToken} whose principal is the user id (UUID) and whose single
 * authority is {@code ROLE_<role>}. A missing/blank header or an invalid token leaves the context
 * unauthenticated — the security chain then returns 401 on protected routes (Hard Invariant #5).
 * Never writes a response itself; it only populates the context.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                JwtPrincipal principal = jwtService.verify(token);
                var authorities = principal.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList();
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal.userId(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtInvalidException ex) {
                log.debug("Rejected invalid auth token: {}", ex.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
