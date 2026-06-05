package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthAppService;
import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.AuthenticationFailedException;
import com.hireai.application.biz.auth.LoginInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Login orchestration. Looks up the user by email, verifies the BCrypt password against the stored
 * hash, checks the account is active, then issues a JWT bound to the user id + role. Every failure
 * mode collapses to {@link AuthenticationFailedException} (generic 401) — no user enumeration. The
 * password check always runs against a real BCrypt verify; an unknown email returns early but the
 * timing difference is acceptable for this FYP slice (account-lockout / constant-time is out of scope).
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class AuthAppServiceImpl implements AuthAppService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final long jwtTtlSeconds;

    public AuthAppServiceImpl(UserRepository userRepository,
                              JwtService jwtService,
                              PasswordEncoder passwordEncoder,
                              @Value("${hireai.auth.jwt-ttl-seconds}") long jwtTtlSeconds) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTtlSeconds = jwtTtlSeconds;
    }

    @Override
    public AuthResult login(LoginInfo loginInfo) {
        UserModel user = userRepository.findByEmail(loginInfo.email())
                .orElseThrow(AuthenticationFailedException::new);
        if (!user.active()) {
            throw new AuthenticationFailedException();
        }
        if (user.passwordHash() == null
                || !passwordEncoder.matches(loginInfo.password(), user.passwordHash())) {
            throw new AuthenticationFailedException();
        }
        String role = user.role().name();
        String token = jwtService.issue(user.id(), role, Duration.ofSeconds(jwtTtlSeconds));
        log.info("User {} logged in (role {})", user.id(), role);
        return new AuthResult(token, user.id(), role);
    }
}
