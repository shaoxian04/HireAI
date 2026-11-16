package com.hireai.infrastructure.security;

import com.hireai.application.port.security.SourceLinkSigner;
import com.hireai.infrastructure.security.impl.HmacDispatchTokenService;
import com.hireai.infrastructure.security.impl.HmacSourceLinkSigner;
import com.hireai.utility.exception.DispatchTokenInvalidException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The signed source link token (spec S4, D31): unforgeable, scoped to one Item, never a dispatch token. */
class HmacSourceLinkSignerTest {

    private static final String SECRET = "a-test-secret-of-sufficient-length";
    private static final Instant DEADLINE = Instant.parse("2026-11-18T10:05:00Z");
    private final HmacSourceLinkSigner signer = new HmacSourceLinkSigner(SECRET);

    @Test
    void aTokenNamesItsItemAndExpiry() {
        UUID item = UUID.randomUUID();
        assertThat(signer.verify(signer.sign(item, DEADLINE)))
                .contains(new SourceLinkSigner.Claims(item, DEADLINE));
    }

    @Test
    void anItemIdMovedUnderAnotherTokensMacOrAnyChangedByteIsRefused() {
        String a = signer.sign(UUID.randomUUID(), DEADLINE);
        String b = signer.sign(UUID.randomUUID(), DEADLINE);
        assertThat(signer.verify(b.split("\\.")[0] + "." + a.split("\\.")[1])).isEmpty();

        byte[] payload = Base64.getUrlDecoder().decode(a.split("\\.")[0]);
        payload[payload.length - 1] ^= 1; // a later expiry
        String extended = Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." + a.split("\\.")[1];
        assertThat(signer.verify(extended)).isEmpty();

        // The MAC's last base64 character carries 2 ignored bits: another spelling of the same bytes is refused.
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        String respelled = a.substring(0, a.length() - 1)
                + alphabet.charAt(alphabet.indexOf(a.charAt(a.length() - 1)) + 1);
        assertThat(Base64.getUrlDecoder().decode(respelled.split("\\.")[1]))
                .isEqualTo(Base64.getUrlDecoder().decode(a.split("\\.")[1])); // the same bytes...
        assertThat(signer.verify(respelled)).isEmpty(); // ...but not the token that was issued

        assertThat(signer.verify(null)).isEmpty();
        assertThat(signer.verify("nodot")).isEmpty();
        assertThat(signer.verify("a.b.c")).isEmpty();
        assertThat(signer.verify("!!.??")).isEmpty();
        assertThat(new HmacSourceLinkSigner("another-secret-of-sufficient-length").verify(a)).isEmpty();
    }

    @Test
    void tokensAreUnguessableAndNotInterchangeableWithDispatchTokens() {
        UUID item = UUID.randomUUID();
        Set<String> macs = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            macs.add(signer.sign(UUID.randomUUID(), DEADLINE).split("\\.")[1]);
        }
        assertThat(macs).hasSize(50);
        assertThat(Base64.getUrlDecoder().decode(signer.sign(item, DEADLINE).split("\\.")[1])).hasSize(32);

        HmacDispatchTokenService dispatch = new HmacDispatchTokenService(SECRET,
                Clock.fixed(DEADLINE.minusSeconds(60), ZoneOffset.UTC));
        assertThatThrownBy(() -> dispatch.verify(signer.sign(item, DEADLINE)))
                .isInstanceOf(DispatchTokenInvalidException.class);
        assertThat(signer.verify(dispatch.issue(item, UUID.randomUUID(), DEADLINE))).isEmpty();
    }
}
