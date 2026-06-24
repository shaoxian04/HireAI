package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.utility.exception.EmailAlreadyRegisteredException;
import com.hireai.application.biz.auth.RegisterInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

/** Unit tests for registration: creates a CLIENT + wallet, hashes the password, rejects duplicates. */
class AuthAppServiceRegisterTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuthAppServiceImpl service =
            new AuthAppServiceImpl(userRepository, walletRepository, jwtService, encoder, 86400L);

    @Test
    void registersClientHashesPasswordAndProvisionsWallet() {
        when(userRepository.findByEmail("new@hireai.local")).thenReturn(Optional.empty());
        when(userRepository.create(any(UserModel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issue(any(UUID.class), eq(List.of("CLIENT")), any(Duration.class)))
                .thenReturn("signed.jwt");

        AuthResult result = service.register(new RegisterInfo("new@hireai.local", "Sup3rSecret!", "Newbie"));

        assertThat(result.token()).isEqualTo("signed.jwt");
        assertThat(result.roles()).containsExactly("CLIENT");

        ArgumentCaptor<UserModel> userCaptor = ArgumentCaptor.forClass(UserModel.class);
        verify(userRepository).create(userCaptor.capture());
        UserModel created = userCaptor.getValue();
        assertThat(created.email()).isEqualTo("new@hireai.local");
        assertThat(created.displayName()).isEqualTo("Newbie");
        assertThat(encoder.matches("Sup3rSecret!", created.passwordHash())).isTrue();

        ArgumentCaptor<WalletModel> walletCaptor = ArgumentCaptor.forClass(WalletModel.class);
        verify(walletRepository).save(walletCaptor.capture());
        assertThat(walletCaptor.getValue().userId()).isEqualTo(created.id());
        assertThat(walletCaptor.getValue().available().value().signum()).isZero();
    }

    @Test
    void rejectsDuplicateEmailAndProvisionsNothing() {
        when(userRepository.findByEmail("taken@hireai.local")).thenReturn(
                Optional.of(UserModel.newClient("taken@hireai.local", "h", "T")));

        assertThatThrownBy(() -> service.register(new RegisterInfo("taken@hireai.local", "whatever1!", null)))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(userRepository, never()).create(any());
        verify(walletRepository, never()).save(any());
        verify(jwtService, never()).issue(any(), anyList(), any());
    }
}
