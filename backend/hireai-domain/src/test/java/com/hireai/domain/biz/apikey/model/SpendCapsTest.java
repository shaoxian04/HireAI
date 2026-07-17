package com.hireai.domain.biz.apikey.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpendCapsTest {
    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void uncappedNeverThrows() {
        SpendCaps.of(null, null).checkOrThrow(bd("999"), bd("999"), bd("10"));
    }

    @Test
    void concurrentUnderCapPasses() {
        SpendCaps.of(bd("100"), null).checkOrThrow(bd("80"), bd("0"), bd("20"));
    }

    @Test
    void concurrentOverCapThrows() {
        assertThatThrownBy(() -> SpendCaps.of(bd("100"), null)
                .checkOrThrow(bd("90"), bd("0"), bd("20")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.SPEND_CAP_EXCEEDED));
    }

    @Test
    void dailyOverCapThrows() {
        assertThatThrownBy(() -> SpendCaps.of(null, bd("50"))
                .checkOrThrow(bd("0"), bd("40"), bd("20")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.SPEND_CAP_EXCEEDED));
    }
}
