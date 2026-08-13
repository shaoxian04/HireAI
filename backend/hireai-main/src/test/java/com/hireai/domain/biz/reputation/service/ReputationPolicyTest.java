package com.hireai.domain.biz.reputation.service;

import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0003 §consequences: a bad reputation configuration must crash bean creation rather than
 * silently produce a wrong marketplace. Mirrors MatchingPolicyTest.
 */
class ReputationPolicyTest {

    @Test
    void defaultsMatchTheAdr() {
        ReputationPolicy p = ReputationPolicy.defaults();
        assertThat(p.alpha()).isEqualTo(0.70);
        assertThat(p.reliabilityPriorStrength()).isEqualTo(5.0);
        assertThat(p.satisfactionPriorStrength()).isEqualTo(10.0);
        assertThat(p.prior()).isEqualTo(0.50);
    }

    @Test
    void rejectsAlphaOutOfUnitRange() {
        assertThatThrownBy(() -> new ReputationPolicy(1.5, 5.0, 10.0, 0.5))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("alpha");
    }

    @Test
    void rejectsPriorOutOfUnitRange() {
        assertThatThrownBy(() -> new ReputationPolicy(0.7, 5.0, 10.0, 1.2))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("prior");
    }

    /**
     * A zero prior strength would divide by zero for a brand-new agent — the cold-start case that
     * must land on exactly 50.00.
     */
    @Test
    void rejectsNonPositivePriorStrength() {
        assertThatThrownBy(() -> new ReputationPolicy(0.7, 0.0, 10.0, 0.5))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("reliabilityPriorStrength");
        assertThatThrownBy(() -> new ReputationPolicy(0.7, 5.0, -1.0, 0.5))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("satisfactionPriorStrength");
    }

    @Test
    void alphaAtEitherBoundIsValid() {
        assertThat(new ReputationPolicy(0.0, 5.0, 10.0, 0.5).alpha()).isZero();
        assertThat(new ReputationPolicy(1.0, 5.0, 10.0, 0.5).alpha()).isEqualTo(1.0);
    }
}
