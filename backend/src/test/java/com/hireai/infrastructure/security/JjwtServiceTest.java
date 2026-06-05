package com.hireai.infrastructure.security;

import com.hireai.application.port.security.JwtInvalidException;
import com.hireai.application.port.security.JwtPrincipal;
import com.hireai.infrastructure.security.impl.JjwtService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the HS256 JjwtService: round-trip, tamper rejection, expiry rejection. */
class JjwtServiceTest {

    private static final String SECRET = "test-only-jwt-secret-at-least-32-bytes-long!!";
    private final JjwtService service = new JjwtService(SECRET);

    @Test
    void issuesAndVerifiesRoundTrip() {
        UUID userId = UUID.randomUUID();

        String token = service.issue(userId, "CLIENT", Duration.ofMinutes(5));
        JwtPrincipal principal = service.verify(token);

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.role()).isEqualTo("CLIENT");
    }

    @Test
    void rejectsTamperedToken() {
        String token = service.issue(UUID.randomUUID(), "BUILDER", Duration.ofMinutes(5));
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("a") ? "b" : "a") + token.charAt(token.length() - 1);

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(JwtInvalidException.class);
    }

    @Test
    void rejectsWrongSecret() {
        String token = service.issue(UUID.randomUUID(), "CLIENT", Duration.ofMinutes(5));
        JjwtService other = new JjwtService("a-different-secret-also-at-least-32-bytes!!");

        assertThatThrownBy(() -> other.verify(token))
                .isInstanceOf(JwtInvalidException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String token = service.issue(UUID.randomUUID(), "CLIENT", Duration.ofSeconds(-1));

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
