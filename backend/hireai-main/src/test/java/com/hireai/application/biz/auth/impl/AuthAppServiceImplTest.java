package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.utility.exception.AuthenticationFailedException;
import com.hireai.application.biz.auth.LoginInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for login orchestration: success issues a token; every failure path throws the generic 401. */
class AuthAppServiceImplTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuthAppServiceImpl service =
            new AuthAppServiceImpl(userRepository, walletRepository, jwtService, encoder, 86400L);

    @Test
    void issuesTokenOnValidCredentials() {
        UUID userId = UUID.randomUUID();
        String hash = encoder.encode("correct-horse");
        when(userRepository.findByEmail("a@hireai.local"))
                .thenReturn(Optional.of(new UserModel(userId, "a@hireai.local", hash, "A", java.util.Set.of(Role.CLIENT), true)));
        when(jwtService.issue(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(java.util.List.of("CLIENT")), any(Duration.class)))
                .thenReturn("signed.jwt.token");

        AuthResult result = service.login(new LoginInfo("a@hireai.local", "correct-horse"));

        assertThat(result.token()).isEqualTo("signed.jwt.token");
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.roles()).containsExactly("CLIENT");
    }

    @Test
    void throwsOnWrongPassword() {
        UUID userId = UUID.randomUUID();
        String hash = encoder.encode("correct-horse");
        when(userRepository.findByEmail("a@hireai.local"))
                .thenReturn(Optional.of(new UserModel(userId, "a@hireai.local", hash, "A", java.util.Set.of(Role.CLIENT), true)));

        assertThatThrownBy(() -> service.login(new LoginInfo("a@hireai.local", "wrong")))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void throwsOnUnknownEmail() {
        when(userRepository.findByEmail("ghost@hireai.local")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginInfo("ghost@hireai.local", "whatever")))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void throwsOnInactiveUser() {
        UUID userId = UUID.randomUUID();
        String hash = encoder.encode("correct-horse");
        when(userRepository.findByEmail("a@hireai.local"))
                .thenReturn(Optional.of(new UserModel(userId, "a@hireai.local", hash, "A", java.util.Set.of(Role.CLIENT), false)));

        assertThatThrownBy(() -> service.login(new LoginInfo("a@hireai.local", "correct-horse")))
                .isInstanceOf(AuthenticationFailedException.class);
    }
}
