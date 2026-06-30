package com.hireai.controller.config;

import com.hireai.application.port.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Profile-scoped security chains.
 *
 * <p><b>Default / prod / dev</b> ({@code @Profile("!test")}): the stateless, CSRF-off,
 * JWT-authenticated {@code securedFilterChain} ({@code @Order(2)}). Only login, register, the agent
 * result callback (dispatch-token authenticated — Hard Invariant #6), and the health probe are
 * public; everything else requires a valid JWT. The {@link JwtAuthenticationFilter} runs before the
 * username/password filter; an unauthenticated request to a protected route gets a 401 (no redirect).
 * When OAuth is enabled ({@code hireai.auth.oauth2.enabled=true}, the {@code oauth} profile), a
 * higher-precedence {@code oauthFilterChain} ({@code @Order(1)}) additionally handles the Google
 * handshake endpoints; the two run side by side.</p>
 *
 * <p><b>{@code test}</b>: a permissive chain (permitAll, CSRF off) so the existing integration / controller
 * tests run with {@link DevCurrentUserProvider} and do not need to mint tokens. Activated by
 * {@code @ActiveProfiles("test")}.</p>
 */
@Configuration
public class SecurityConfig {

    /**
     * Dedicated chain for the Google OAuth handshake. Higher precedence than the JWT chain; scoped to
     * the OAuth endpoints only. Allows the session the authorization-request repository needs. Loads
     * only when OAuth is enabled and a client is configured.
     */
    @Bean
    @Order(1)
    @Profile("!test")
    @ConditionalOnProperty(name = "hireai.auth.oauth2.enabled", havingValue = "true")
    public SecurityFilterChain oauthFilterChain(
            HttpSecurity http,
            OAuth2AuthenticationSuccessHandler successHandler,
            NonceCarryingAuthorizationRequestResolver nonceResolver,
            @Value("${hireai.auth.oauth2.failure-redirect-url}") String failureUrl)
            throws Exception {
        http
                .securityMatcher("/oauth2/**", "/login/oauth2/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(a -> a.authorizationRequestResolver(nonceResolver))
                        .successHandler(successHandler)
                        .failureUrl(failureUrl + "?error=oauth"));
        return http.build();
    }

    @Bean
    @Order(2)
    @Profile("!test")
    public SecurityFilterChain securedFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/register").permitAll()
                        .requestMatchers("/api/agent-callbacks/**").permitAll()
                        .requestMatchers("/api/arbitration-callbacks/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Profile("test")
    public SecurityFilterChain permissiveFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
