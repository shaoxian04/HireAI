package com.hireai.webhook;

import com.hireai.domain.biz.webhook.WebhookSignature;
import com.hireai.utility.hash.HmacSha256;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    // Known HMAC-SHA256("secret", "1614556800.{\"a\":1}") vector (lowercase hex).
    @Test void hmacHexIsDeterministicAndLowercase() {
        String h = HmacSha256.hexOf("secret", "hello");
        assertThat(h).isEqualTo("88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b");
        assertThat(h).matches("[0-9a-f]{64}");
    }

    @Test void headerBindsTimestampThenBody() {
        String header = WebhookSignature.header("secret", 1614556800L, "{\"a\":1}");
        String v1 = HmacSha256.hexOf("secret", "1614556800.{\"a\":1}");
        assertThat(header).isEqualTo("t=1614556800,v1=" + v1);
    }

    @Test void differentBodyChangesSignature() {
        assertThat(WebhookSignature.header("s", 1L, "{\"a\":1}"))
                .isNotEqualTo(WebhookSignature.header("s", 1L, "{\"a\":2}"));
    }
}
