package com.hireai.domain.biz.agent.model;

import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingTest {

    @Test
    void buildsFromNonNegativeAmountAndNormalisesScale() {
        Pricing pricing = Pricing.of(new BigDecimal("10"));
        assertThat(pricing.price()).isEqualByComparingTo("10.00");
    }

    @Test
    void acceptsZero() {
        assertThat(Pricing.of(BigDecimal.ZERO).price()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Pricing.of(null)).isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNegative() {
        assertThatThrownBy(() -> Pricing.of(new BigDecimal("-1"))).isInstanceOf(DomainException.class);
    }
}
