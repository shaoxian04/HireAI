package com.hireai.infrastructure.security.impl;

import com.hireai.application.port.security.JwtInvalidException;
import com.hireai.application.port.security.JwtPrincipal;
import com.hireai.application.port.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * HS256 JWT issue/verify backed by io.jsonwebtoken (jjwt). The subject is the user id; a custom
 * {@code role} claim carries the role. The signing secret is a server-side env value (>= 32 bytes
 * for HS256). {@code verify} throws {@link JwtInvalidException} on a bad signature, malformed token,
 * or expiry. Mirrors {@link HmacDispatchTokenService}'s config-secret + exception style.
 */
@Service
@Slf4j
public class JjwtService implements JwtService {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;

    public JjwtService(@Value("${hireai.auth.jwt-secret}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("hireai.auth.jwt-secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String issue(UUID userId, String role, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(ROLE_CLAIM, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    @Override
    public JwtPrincipal verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            String role = claims.get(ROLE_CLAIM, String.class);
            return new JwtPrincipal(userId, role);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new JwtInvalidException("Invalid authentication token", ex);
        }
    }
}
