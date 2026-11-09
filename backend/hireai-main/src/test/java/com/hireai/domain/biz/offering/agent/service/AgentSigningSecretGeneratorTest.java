package com.hireai.domain.biz.offering.agent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The AgentSigningSecret contract: prefixed, 256-bit hex, and non-repeating across calls. */
class AgentSigningSecretGeneratorTest {

    private final AgentSigningSecretGenerator gen = new AgentSigningSecretGenerator();

    @Test
    void generatesPrefixed256BitHexSecret() {
        String secret = gen.generate();
        assertThat(secret).startsWith("agsec_");
        assertThat(secret.substring("agsec_".length())).matches("[0-9a-f]{64}");
    }

    @Test
    void twoSecretsDiffer() {
        assertThat(gen.generate()).isNotEqualTo(gen.generate());
    }
}
