package com.hireai.controller.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Single application-wide password encoder. BCrypt verifies the seeded demo password hashes
 * (Flyway V5) during login. Profile-independent so both the secured default chain and the
 * permissive test chain share one encoder bean.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
