package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthAppService;
import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.AuthenticationFailedException;
import com.hireai.application.biz.auth.EmailAlreadyRegisteredException;
import com.hireai.application.biz.auth.LoginInfo;
import com.hireai.application.biz.auth.RegisterInfo;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Login orchestration. Looks up the user by email, verifies the BCrypt password against the stored
 * hash, checks the account is active, then issues a JWT bound to the user id + role set. Every failure
 * mode collapses to {@link AuthenticationFailedException} (generic 401) — no user enumeration.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class AuthAppServiceImpl implements AuthAppService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final long jwtTtlSeconds;

    public AuthAppServiceImpl(UserRepository userRepository,
                              WalletRepository walletRepository,
                              JwtService jwtService,
                              PasswordEncoder passwordEncoder,
                              @Value("${hireai.auth.jwt-ttl-seconds}") long jwtTtlSeconds) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTtlSeconds = jwtTtlSeconds;
    }

    @Override
    @Transactional
    public AuthResult register(RegisterInfo info) {
        if (userRepository.findByEmail(info.email()).isPresent()) {
            throw new EmailAlreadyRegisteredException();
        }
        String hash = passwordEncoder.encode(info.password());
        UserModel user = userRepository.create(
                UserModel.newClient(info.email(), hash, info.displayName()));
        walletRepository.save(WalletModel.openFor(user.id()));

        List<String> roles = user.roles().stream()
                .map(com.hireai.domain.biz.user.enums.Role::name).sorted().toList();
        String token = jwtService.issue(user.id(), roles, Duration.ofSeconds(jwtTtlSeconds));
        log.info("Registered new user {} (roles {})", user.id(), roles);
        return new AuthResult(token, user.id(), roles);
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
        List<String> roles = user.roles().stream()
                .map(com.hireai.domain.biz.user.enums.Role::name).sorted().toList();
        String token = jwtService.issue(user.id(), roles, Duration.ofSeconds(jwtTtlSeconds));
        log.info("User {} logged in (roles {})", user.id(), roles);
        return new AuthResult(token, user.id(), roles);
    }
}
