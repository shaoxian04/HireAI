package com.hireai.application.biz.identity.impl;

import com.hireai.application.biz.identity.AuthAppService;
import com.hireai.application.biz.identity.AuthResult;
import com.hireai.utility.exception.AuthenticationFailedException;
import com.hireai.utility.exception.EmailAlreadyRegisteredException;
import com.hireai.application.biz.identity.LoginInfo;
import com.hireai.application.biz.identity.RegisterInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.identity.enums.Role;
import com.hireai.domain.biz.identity.model.Credential;
import com.hireai.domain.biz.identity.model.UserModel;
import com.hireai.domain.biz.identity.repository.UserRepository;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

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
                UserModel.newClient(info.email(), Credential.ofHash(hash), info.displayName()));
        walletRepository.save(WalletModel.openFor(user.id()));

        List<String> roles = user.roles().stream()
                .map(Role::name).sorted().toList();
        String token = jwtService.issue(user.id(), roles, Duration.ofSeconds(jwtTtlSeconds));
        log.info("Registered new user {} (roles {})", user.id(), roles);
        return new AuthResult(token, user.id(), roles);
    }

    @Override
    @Transactional
    public AuthResult becomeBuilder(UUID userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + userId));
        userRepository.addRole(userId, Role.BUILDER);

        UserModel updated = userRepository.findById(userId).orElseThrow();
        List<String> roles = updated.roles().stream()
                .map(Role::name).sorted().toList();
        String token = jwtService.issue(userId, roles, Duration.ofSeconds(jwtTtlSeconds));
        log.info("User {} upgraded to builder (roles {})", userId, roles);
        return new AuthResult(token, userId, roles);
    }

    @Override
    public AuthResult login(LoginInfo loginInfo) {
        UserModel user = userRepository.findByEmail(loginInfo.email())
                .orElseThrow(AuthenticationFailedException::new);
        if (!user.active()) {
            throw new AuthenticationFailedException();
        }
        if (user.credential().isAbsent()
                || !passwordEncoder.matches(loginInfo.password(), user.credential().secretHash())) {
            throw new AuthenticationFailedException();
        }
        List<String> roles = user.roles().stream()
                .map(Role::name).sorted().toList();
        String token = jwtService.issue(user.id(), roles, Duration.ofSeconds(jwtTtlSeconds));
        log.info("User {} logged in (roles {})", user.id(), roles);
        return new AuthResult(token, user.id(), roles);
    }
}
