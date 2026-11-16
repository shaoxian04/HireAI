package com.hireai.infrastructure.security.impl;

import com.hireai.application.port.security.SourceLinkSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * HMAC-SHA256 source-link token: {@code base64url(itemId[16] | exp[8]).base64url(mac[32])}. The key is
 * derived from {@code hireai.dispatch.token-secret} for this one purpose (HMAC of a fixed label), so a
 * source-link token is never a valid dispatch token and vice versa. Constant-time MAC comparison.
 */
@Service
public class HmacSourceLinkSigner implements SourceLinkSigner {

    private static final String HMAC = "HmacSHA256";
    private static final byte[] PURPOSE = "hireai/source-link/v1".getBytes(StandardCharsets.UTF_8);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int PAYLOAD_BYTES = 24;

    private final byte[] key;

    public HmacSourceLinkSigner(@Value("${hireai.dispatch.token-secret}") String tokenSecret) {
        if (tokenSecret == null || tokenSecret.length() < 16) {
            throw new IllegalStateException("hireai.dispatch.token-secret must be at least 16 characters");
        }
        this.key = mac(tokenSecret.getBytes(StandardCharsets.UTF_8), PURPOSE);
    }

    @Override
    public String sign(UUID itemId, Instant expiresAt) {
        byte[] payload = ByteBuffer.allocate(PAYLOAD_BYTES)
                .putLong(itemId.getMostSignificantBits()).putLong(itemId.getLeastSignificantBits())
                .putLong(expiresAt.getEpochSecond()).array();
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(mac(key, payload));
    }

    @Override
    public Optional<Claims> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        byte[] payload;
        byte[] given;
        try {
            payload = DECODER.decode(token.substring(0, dot));
            given = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // Only the canonical encoding: base64 lets a final character carry ignored bits, so several
        // spellings decode alike. One token per Item and expiry keeps the logged links unambiguous.
        String canonical = ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(given);
        if (payload.length != PAYLOAD_BYTES || !canonical.equals(token)
                || !MessageDigest.isEqual(mac(key, payload), given)) {
            return Optional.empty();
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        UUID itemId = new UUID(buffer.getLong(), buffer.getLong());
        return Optional.of(new Claims(itemId, Instant.ofEpochSecond(buffer.getLong())));
    }

    private static byte[] mac(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute HMAC", e);
        }
    }
}
