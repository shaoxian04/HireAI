package com.hireai.utility.hash;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {
    @Test
    void producesStable64CharLowercaseHex() {
        String h = Sha256.hex("hk_live_abc");
        assertThat(h).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(Sha256.hex("hk_live_abc")).isEqualTo(h); // deterministic
        assertThat(Sha256.hex("hk_live_abd")).isNotEqualTo(h); // sensitive
    }
}
