package com.hireai.webhook;

import com.hireai.domain.biz.webhook.service.WebhookSecretGenerator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Guards the signing-secret contract: prefixed, high-entropy hex, and non-repeating across calls. */
class WebhookSecretGeneratorTest {
    private final WebhookSecretGenerator gen = new WebhookSecretGenerator();

    @Test void generatesPrefixed256BitHexSecret() {
        String secret = gen.generate();
        assertThat(secret).startsWith("whsec_");
        assertThat(secret.substring("whsec_".length())).matches("[0-9a-f]{64}"); // 32 bytes = 256 bits
    }

    @Test void twoSecretsDiffer() {
        assertThat(gen.generate()).isNotEqualTo(gen.generate()); // SecureRandom → effectively never collides
    }
}
