package com.hireai.controller.config;

import com.hireai.application.port.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two profile-scoped security chains; exactly one is active per profile.
 *
 * <p><b>Default / prod / dev</b> ({@code @Profile("!test")}): stateless, CSRF off, JWT-authenticated by
 * default. Only login, the agent result callback (dispatch-token authenticated — Hard Invariant #6),
 * and the health probe are public; everything else requires a valid JWT. The {@link JwtAuthenticationFilter}
 * runs before the username/password filter and populates the {@code SecurityContext}; an unauthenticated
 * request to a protected route gets a 401 (no redirect).</p>
 *
 * <p><b>{@code test}</b>: a permissive chain (permitAll, CSRF off) so the existing integration / controller
 * tests run with {@link DevCurrentUserProvider} and do not need to mint tokens. Activated by
 * {@code @ActiveProfiles("test")}.</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Profile("!test")
    public SecurityFilterChain securedFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/register").permitAll()
                        .requestMatchers("/api/agent-callbacks/**").permitAll()
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
