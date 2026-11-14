package com.hireai.webhook;

import com.hireai.domain.biz.task.webhookdelivery.WebhookSignature;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifying an inbound {@code X-HireAI-Signature} (the Agent result callback): the scheme a Builder
 * signs with is HMAC-SHA256(secret, "<ts>." + raw body bytes), header {@code t=<ts>,v1=<hex>}.
 * Expected signatures are computed here with the JDK's Mac, independently of the platform helper.
 */
class WebhookSignatureVerifyTest {

    private static final String SECRET = "agsec_test";
    private static final long TS = 1_614_556_800L;
    private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final Instant NOW = Instant.ofEpochSecond(TS);

    private static String sign(String secret, long ts, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + ".").getBytes(StandardCharsets.UTF_8));
            return "t=" + ts + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean verify(String secret, String header, byte[] body, Instant now) {
        return WebhookSignature.verify(secret, header, body, now, WINDOW);
    }

    @Test void aCorrectSignatureWithinTheWindowVerifies() {
        String header = sign(SECRET, TS, BODY);
        assertThat(verify(SECRET, header, BODY, NOW)).isTrue();
        assertThat(verify(SECRET, header, BODY, NOW.plusSeconds(300))).isTrue();
        assertThat(verify(SECRET, header, BODY, NOW.minusSeconds(300))).isTrue();
    }

    @Test void theOutboundHeaderVerifies() {
        // What the platform sends on WorkOrders verifies with the same scheme.
        String header = WebhookSignature.header(SECRET, TS, new String(BODY, StandardCharsets.UTF_8));
        assertThat(header).isEqualTo(sign(SECRET, TS, BODY));
        assertThat(verify(SECRET, header, BODY, NOW)).isTrue();
    }

    @Test void aMissingOrMalformedHeaderIsRejected() {
        String good = sign(SECRET, TS, BODY);
        assertThat(verify(SECRET, null, BODY, NOW)).isFalse();
        assertThat(verify(SECRET, "", BODY, NOW)).isFalse();
        assertThat(verify(SECRET, "garbage", BODY, NOW)).isFalse();
        assertThat(verify(SECRET, good.replace("t=" + TS, "t=abc"), BODY, NOW)).isFalse();
        assertThat(verify(SECRET, good.substring(good.indexOf("v1=")), BODY, NOW)).isFalse();
        assertThat(verify(SECRET, "t=" + TS, BODY, NOW)).isFalse();
        assertThat(verify(SECRET, good.toUpperCase(), BODY, NOW)).isFalse();
    }

    @Test void theWrongSecretOrAnAlteredBodyIsRejected() {
        String good = sign(SECRET, TS, BODY);
        assertThat(verify("agsec_other", good, BODY, NOW)).isFalse();
        assertThat(verify(SECRET, good, "{\"a\":2}".getBytes(StandardCharsets.UTF_8), NOW)).isFalse();
    }

    @Test void aTimestampOutsideTheWindowIsRejectedEvenWhenTheMacIsRight() {
        String good = sign(SECRET, TS, BODY);
        assertThat(verify(SECRET, good, BODY, NOW.plusSeconds(301))).isFalse();
        assertThat(verify(SECRET, good, BODY, NOW.minusSeconds(301))).isFalse();
    }

    @Test void anyMatchingV1IsAccepted() {
        String good = sign(SECRET, TS, BODY);
        String header = "t=" + TS + ",v1=" + "0".repeat(64) + "," + good.substring(good.indexOf("v1="));
        assertThat(verify(SECRET, header, BODY, NOW)).isTrue();
    }

    @Test void theRawBytesAreWhatIsSigned() {
        // Not valid UTF-8: a verifier that decoded the body to a String first would not match.
        byte[] raw = {(byte) 0x7B, (byte) 0xC3, (byte) 0x28, (byte) 0x7D};
        assertThat(verify(SECRET, sign(SECRET, TS, raw), raw, NOW)).isTrue();
    }

    @Test void anEmptyBodyCanBeVerified() {
        assertThat(verify(SECRET, sign(SECRET, TS, new byte[0]), new byte[0], NOW)).isTrue();
    }
}
