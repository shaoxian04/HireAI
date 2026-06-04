package com.hireai.controller.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal security configuration for the current scaffold.
 *
 * TODO(auth): JWT authentication + RBAC are not yet implemented. Until they are,
 * endpoints are permitted so the Wallet slice is runnable. Per the project hard
 * invariant "server-side identity from JWT", once auth lands the current user
 * MUST be derived from the JWT principal (see CurrentUserProvider) and this
 * filter chain MUST require authentication.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
