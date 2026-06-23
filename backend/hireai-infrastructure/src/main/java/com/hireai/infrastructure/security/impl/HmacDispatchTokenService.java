package com.hireai.infrastructure.security.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenInvalidException;
import com.hireai.application.port.security.DispatchTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * HMAC-SHA256 compact dispatch token: {@code base64url(payloadJson).base64url(signature)}.
 * Payload carries {@code taskId, agentVersionId, exp (epoch seconds)}. The secret is a
 * server-side env value; tokens are short-lived and never stored. {@code verify} rejects a
 * bad signature (constant-time compare) and expiry by throwing {@link DispatchTokenInvalidException}.
 */
@Service
@Slf4j
public class HmacDispatchTokenService implements DispatchTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final byte[] secretKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HmacDispatchTokenService(@Value("${hireai.dispatch.token-secret}") String tokenSecret) {
        if (tokenSecret == null || tokenSecret.length() < 16) {
            throw new IllegalStateException("hireai.dispatch.token-secret must be at least 16 characters");
        }
        this.secretKey = tokenSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String issue(UUID taskId, UUID agentVersionId, Duration ttl) {
        long exp = Instant.now().plus(ttl).getEpochSecond();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", taskId.toString());
        payload.put("agentVersionId", agentVersionId.toString());
        payload.put("exp", exp);
        String payloadJson = payload.toString();
        String encodedPayload = URL_ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signature = URL_ENCODER.encodeToString(sign(encodedPayload));
        return encodedPayload + "." + signature;
    }

    @Override
    public DispatchTokenClaims verify(String token) {
        if (token == null) {
            throw new DispatchTokenInvalidException("Token missing");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new DispatchTokenInvalidException("Malformed token");
        }
        String encodedPayload = token.substring(0, dot);
        String providedSignature = token.substring(dot + 1);

        byte[] expected = sign(encodedPayload);
        byte[] provided;
        try {
            provided = URL_DECODER.decode(providedSignature);
        } catch (IllegalArgumentException ex) {
            throw new DispatchTokenInvalidException("Malformed signature");
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new DispatchTokenInvalidException("Signature mismatch");
        }

        try {
            String payloadJson = new String(URL_DECODER.decode(encodedPayload), StandardCharsets.UTF_8);
            var node = objectMapper.readTree(payloadJson);
            UUID taskId = UUID.fromString(node.get("taskId").asText());
            UUID agentVersionId = UUID.fromString(node.get("agentVersionId").asText());
            Instant expiresAt = Instant.ofEpochSecond(node.get("exp").asLong());
            if (expiresAt.isBefore(Instant.now())) {
                throw new DispatchTokenInvalidException("Token expired");
            }
            return new DispatchTokenClaims(taskId, agentVersionId, expiresAt);
        } catch (DispatchTokenInvalidException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DispatchTokenInvalidException("Unparseable token payload");
        }
    }

    private byte[] sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute HMAC", ex);
        }
    }
}
