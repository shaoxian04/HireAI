package com.hireai.application.biz.task;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class SubmitFingerprintTest {
    @Test
    void identicalPayloadsProduceSameFingerprint() {
        String a = SubmitFingerprint.of("T", "desc", "cat", new BigDecimal("10.00"), "{\"format\":\"JSON\"}");
        String b = SubmitFingerprint.of("T", "desc", "cat", new BigDecimal("10.00"), "{\"format\":\"JSON\"}");
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void anyFieldChangeChangesFingerprint() {
        String base = SubmitFingerprint.of("T", "desc", "cat", new BigDecimal("10.00"), "{}");
        assertThat(SubmitFingerprint.of("T2", "desc", "cat", new BigDecimal("10.00"), "{}")).isNotEqualTo(base);
        assertThat(SubmitFingerprint.of("T", "desc2", "cat", new BigDecimal("10.00"), "{}")).isNotEqualTo(base);
        assertThat(SubmitFingerprint.of("T", "desc", "cat2", new BigDecimal("10.00"), "{}")).isNotEqualTo(base);
        assertThat(SubmitFingerprint.of("T", "desc", "cat", new BigDecimal("11.00"), "{}")).isNotEqualTo(base);
        assertThat(SubmitFingerprint.of("T", "desc", "cat", new BigDecimal("10.00"), "{\"x\":1}")).isNotEqualTo(base);
    }

    @Test
    void budgetScaleDoesNotFalselyDiffer() {
        // 10 and 10.00 are the same amount → same fingerprint (normalize scale).
        assertThat(SubmitFingerprint.of("T", "d", "c", new BigDecimal("10"), "{}"))
                .isEqualTo(SubmitFingerprint.of("T", "d", "c", new BigDecimal("10.00"), "{}"));
    }
}
