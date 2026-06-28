package com.hireai.domain.shared.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void scaleIsNormalisedToTwoDecimals() {
        assertThat(Money.of("10").value()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(Money.of("10.005").value()).isEqualByComparingTo(new BigDecimal("10.01"));
    }

    @Test
    void addAndSubtractAreImmutable() {
        Money ten = Money.of("10.00");
        Money five = Money.of("5.00");
        assertThat(ten.add(five)).isEqualTo(Money.of("15.00"));
        assertThat(ten.subtract(five)).isEqualTo(Money.of("5.00"));
        assertThat(ten).isEqualTo(Money.of("10.00")); // unchanged
    }

    @Test
    void comparisonsAndSign() {
        assertThat(Money.of("10.00").isGreaterThan(Money.of("9.99"))).isTrue();
        assertThat(Money.of("0.00").isPositive()).isFalse();
        assertThat(Money.of("-1.00").isNegative()).isTrue();
        assertThat(Money.of("5.00").negated()).isEqualTo(Money.of("-5.00"));
    }

    @Test
    void percentageRoundsHalfUp() {
        assertThat(Money.of("100.00").percentage(new BigDecimal("0.15"))).isEqualTo(Money.of("15.00"));
    }
}
