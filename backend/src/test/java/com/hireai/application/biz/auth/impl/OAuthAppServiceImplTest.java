package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.OAuthAuthenticationException;
import com.hireai.application.biz.auth.OAuthUserInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserIdentityRepository;
import com.hireai.domain.biz.user.repository.UserRepository;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for OAuth resolution: existing link, link-by-email, new account, unverified email. */
class OAuthAppServiceImplTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserIdentityRepository identityRepository = mock(UserIdentityRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final OAuthAppServiceImpl service = new OAuthAppServiceImpl(
            userRepository, identityRepository, walletRepository, jwtService, 86400L);

    private OAuthUserInfo google(String email, boolean verified) {
        return new OAuthUserInfo("google", "sub-123", email, verified, "Ada");
    }

    @Test
    void existingLinkLogsInWithoutCreatingOrLinking() {
        UUID userId = UUID.randomUUID();
        when(identityRepository.findUserIdByProviderSubject("google", "sub-123"))
                .thenReturn(Optional.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new UserModel(userId, "ada@hireai.local", null, "Ada", Set.of(Role.CLIENT, Role.BUILDER), true)));
        when(jwtService.issue(eq(userId), anyList(), any(Duration.class))).thenReturn("jwt");

        AuthResult result = service.loginWithOAuth(google("ada@hireai.local", true));

        assertThat(result.token()).isEqualTo("jwt");
        assertThat(result.roles()).containsExactlyInAnyOrder("CLIENT", "BUILDER");
        verify(userRepository, never()).create(any());
        verify(identityRepository, never()).link(any(), any(), any(), any());
    }

    @Test
    void linksByEmailWhenAccountExistsButIdentityDoesNot() {
        UUID userId = UUID.randomUUID();
        when(identityRepository.findUserIdByProviderSubject("google", "sub-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ada@hireai.local")).thenReturn(Optional.of(
                new UserModel(userId, "ada@hireai.local", "h", "Ada", Set.of(Role.CLIENT), true)));
        when(jwtService.issue(eq(userId), anyList(), any(Duration.class))).thenReturn("jwt");

        AuthResult result = service.loginWithOAuth(google("ada@hireai.local", true));

        assertThat(result.userId()).isEqualTo(userId);
        verify(identityRepository).link(userId, "google", "sub-123", "ada@hireai.local");
        verify(userRepository, never()).create(any());
        verify(walletRepository, never()).save(any());
    }

    @Test
    void createsNewClientWithWalletAndLinkWhenUnknown() {
        when(identityRepository.findUserIdByProviderSubject("google", "sub-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ada@hireai.local")).thenReturn(Optional.empty());
        when(userRepository.create(any(UserModel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issue(any(UUID.class), eq(List.of("CLIENT")), any(Duration.class))).thenReturn("jwt");

        AuthResult result = service.loginWithOAuth(google("ada@hireai.local", true));

        assertThat(result.roles()).containsExactly("CLIENT");
        ArgumentCaptor<UserModel> userCaptor = ArgumentCaptor.forClass(UserModel.class);
        verify(userRepository).create(userCaptor.capture());
        assertThat(userCaptor.getValue().passwordHash()).isNull();
        assertThat(userCaptor.getValue().displayName()).isEqualTo("Ada");

        ArgumentCaptor<WalletModel> walletCaptor = ArgumentCaptor.forClass(WalletModel.class);
        verify(walletRepository).save(walletCaptor.capture());
        assertThat(walletCaptor.getValue().userId()).isEqualTo(userCaptor.getValue().id());
        verify(identityRepository).link(eq(userCaptor.getValue().id()), eq("google"), eq("sub-123"), eq("ada@hireai.local"));
    }

    @Test
    void rejectsUnverifiedEmail() {
        assertThatThrownBy(() -> service.loginWithOAuth(google("ada@hireai.local", false)))
                .isInstanceOf(OAuthAuthenticationException.class);
        verify(userRepository, never()).create(any());
    }
}
