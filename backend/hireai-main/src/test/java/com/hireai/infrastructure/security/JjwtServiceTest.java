package com.hireai.infrastructure.security;

import com.hireai.utility.exception.JwtInvalidException;
import com.hireai.application.port.security.JwtPrincipal;
import com.hireai.infrastructure.security.impl.JjwtService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the HS256 JjwtService: round-trip, tamper rejection, expiry rejection. */
class JjwtServiceTest {

    private static final String SECRET = "test-only-jwt-secret-at-least-32-bytes-long!!";
    private final JjwtService service = new JjwtService(SECRET);

    @Test
    void issuesAndVerifiesRoundTripWithRoleSet() {
        UUID userId = UUID.randomUUID();

        String token = service.issue(userId, List.of("CLIENT", "BUILDER"), Duration.ofMinutes(5));
        JwtPrincipal principal = service.verify(token);

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.roles()).containsExactlyInAnyOrder("CLIENT", "BUILDER");
    }

    @Test
    void rejectsTamperedToken() {
        String token = service.issue(UUID.randomUUID(), List.of("BUILDER"), Duration.ofMinutes(5));
        // Flip a fully-significant char in the signature segment. Choose the replacement
        // relative to the char being replaced so the tamper is never a no-op (the random
        // signature would otherwise occasionally already hold the replacement char → flaky).
        int i = token.length() - 2;
        char replacement = token.charAt(i) == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, i) + replacement + token.charAt(token.length() - 1);

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(JwtInvalidException.class);
    }

    @Test
    void rejectsWrongSecret() {
        String token = service.issue(UUID.randomUUID(), List.of("CLIENT"), Duration.ofMinutes(5));
        JjwtService other = new JjwtService("a-different-secret-also-at-least-32-bytes!!");

        assertThatThrownBy(() -> other.verify(token))
                .isInstanceOf(JwtInvalidException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String token = service.issue(UUID.randomUUID(), List.of("CLIENT"), Duration.ofSeconds(-1));

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(JwtInvalidException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> service.verify("not-a-jwt"))
                .isInstanceOf(JwtInvalidException.class);
    }

    @Test
    void rejectsTooShortSecretAtConstruction() {
        assertThatThrownBy(() -> new JjwtService("too-short"))
                .isInstanceOf(IllegalStateException.class);
    }
}
