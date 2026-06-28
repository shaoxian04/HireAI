package com.hireai.application.biz.identity.impl;

import com.hireai.application.biz.identity.AuthResult;
import com.hireai.application.biz.identity.OAuthAppService;
import com.hireai.utility.exception.OAuthAuthenticationException;
import com.hireai.application.biz.identity.OAuthUserInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.identity.enums.Role;
import com.hireai.domain.biz.identity.model.UserModel;
import com.hireai.domain.biz.identity.repository.UserIdentityRepository;
import com.hireai.domain.biz.identity.repository.UserRepository;
import com.hireai.domain.biz.identity.service.OAuthAccountLinkingDomainService;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
    private final OAuthAccountLinkingDomainService accountLinkingDomainService;
    private final JwtService jwtService;
    private final long jwtTtlSeconds;

    public OAuthAppServiceImpl(UserRepository userRepository,
                               UserIdentityRepository identityRepository,
                               WalletRepository walletRepository,
                               OAuthAccountLinkingDomainService accountLinkingDomainService,
                               JwtService jwtService,
                               @Value("${hireai.auth.jwt-ttl-seconds}") long jwtTtlSeconds) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.walletRepository = walletRepository;
        this.accountLinkingDomainService = accountLinkingDomainService;
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

        // Mirror the password-login active check: a deactivated account must not re-enter via OAuth.
        // (Newly created accounts are always active, so this only rejects existing disabled users.)
        if (!user.active()) {
            throw new OAuthAuthenticationException("Account is disabled");
        }

        return issue(user);
    }

    private UserModel resolveByEmailOrCreate(OAuthUserInfo info) {
        // App-layer orchestration only: read the would-be email collision (read-only query) and let the
        // domain rule decide whether a new account may be seeded. The no-silent-link invariant (the
        // account-takeover guard) lives in OAuthAccountLinkingDomainService so it is owned and tested in
        // the domain, not here. Persistence below goes through the domain repository interfaces.
        Optional<UserModel> existingByEmail = userRepository.findByEmail(info.email());
        accountLinkingDomainService.assertNoLocalAccountForEmail(existingByEmail, info.provider());

        UserModel created = userRepository.create(
                UserModel.newClient(info.email(), null, info.displayName()));
        walletRepository.save(WalletModel.openFor(created.id()));
        identityRepository.link(created.id(), info.provider(), info.subject(), info.email());
        log.info("Created OAuth user {} via {}", created.id(), info.provider());
        return created;
    }

    private AuthResult issue(UserModel user) {
        List<String> roles = user.roles().stream().map(Role::name).sorted().toList();
        String token = jwtService.issue(user.id(), roles, Duration.ofSeconds(jwtTtlSeconds));
        return new AuthResult(token, user.id(), roles);
    }
}
