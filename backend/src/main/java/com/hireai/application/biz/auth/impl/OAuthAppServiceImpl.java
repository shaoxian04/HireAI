package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.OAuthAppService;
import com.hireai.application.biz.auth.OAuthAuthenticationException;
import com.hireai.application.biz.auth.OAuthUserInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserIdentityRepository;
import com.hireai.domain.biz.user.repository.UserRepository;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Resolves a verified OAuth identity to a local account and issues our JWT. Branches: existing
 * identity link → log in; existing email → link then log in; otherwise create a CLIENT + wallet +
 * link. All write paths are one transaction (Hard Invariant #1).
 */
@Service
@Slf4j
public class OAuthAppServiceImpl implements OAuthAppService {

    private final UserRepository userRepository;
    private final UserIdentityRepository identityRepository;
    private final WalletRepository walletRepository;
    private final JwtService jwtService;
    private final long jwtTtlSeconds;

    public OAuthAppServiceImpl(UserRepository userRepository,
                               UserIdentityRepository identityRepository,
                               WalletRepository walletRepository,
                               JwtService jwtService,
                               @Value("${hireai.auth.jwt-ttl-seconds}") long jwtTtlSeconds) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.walletRepository = walletRepository;
        this.jwtService = jwtService;
        this.jwtTtlSeconds = jwtTtlSeconds;
    }

    @Override
    @Transactional
    public AuthResult loginWithOAuth(OAuthUserInfo info) {
        if (!info.emailVerified()) {
            throw new OAuthAuthenticationException("OAuth email is not verified");
        }

        UserModel user = identityRepository
                .findUserIdByProviderSubject(info.provider(), info.subject())
                .flatMap(userRepository::findById)
                .orElseGet(() -> resolveByEmailOrCreate(info));

        return issue(user);
    }

    private UserModel resolveByEmailOrCreate(OAuthUserInfo info) {
        return userRepository.findByEmail(info.email())
                .map(existing -> {
                    identityRepository.link(existing.id(), info.provider(), info.subject(), info.email());
                    return existing;
                })
                .orElseGet(() -> {
                    UserModel created = userRepository.create(
                            UserModel.newClient(info.email(), null, info.displayName()));
                    walletRepository.save(WalletModel.openFor(created.id()));
                    identityRepository.link(created.id(), info.provider(), info.subject(), info.email());
                    log.info("Created OAuth user {} via {}", created.id(), info.provider());
                    return created;
                });
    }

    private AuthResult issue(UserModel user) {
        List<String> roles = user.roles().stream().map(Role::name).sorted().toList();
        String token = jwtService.issue(user.id(), roles, Duration.ofSeconds(jwtTtlSeconds));
        return new AuthResult(token, user.id(), roles);
    }
}
