package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the become-builder upgrade: adds the role, re-issues an expanded token. */
class AuthAppServiceBecomeBuilderTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuthAppServiceImpl service = new AuthAppServiceImpl(
            userRepository, walletRepository, jwtService, new BCryptPasswordEncoder(), 86400L);

    @Test
    void addsBuilderRoleAndReissuesToken() {
        UUID userId = UUID.randomUUID();
        // First load: CLIENT only. After addRole, the reload returns CLIENT + BUILDER.
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(new UserModel(userId, "ada@hireai.local", "h", "Ada", Set.of(Role.CLIENT), true)))
                .thenReturn(Optional.of(new UserModel(userId, "ada@hireai.local", "h", "Ada", Set.of(Role.CLIENT, Role.BUILDER), true)));
        when(jwtService.issue(eq(userId), eq(List.of("BUILDER", "CLIENT")), any(Duration.class)))
                .thenReturn("expanded.jwt");

        AuthResult result = service.becomeBuilder(userId);

        verify(userRepository).addRole(userId, Role.BUILDER);
        assertThat(result.token()).isEqualTo("expanded.jwt");
        assertThat(result.roles()).containsExactly("BUILDER", "CLIENT");
    }
}
