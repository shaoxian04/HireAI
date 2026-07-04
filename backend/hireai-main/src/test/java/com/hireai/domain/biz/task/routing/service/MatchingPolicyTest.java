package com.hireai.domain.biz.task.routing.service;

import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingPolicyTest {

    @Test
    void defaultsAreValidAndMatchTheSpec() {
        MatchingPolicy p = MatchingPolicy.defaults();
        assertThat(p.weightReputation()).isEqualTo(0.40);
        assertThat(p.weightValue()).isEqualTo(0.20);
        assertThat(p.weightLoad()).isEqualTo(0.20);
        assertThat(p.weightExploration()).isEqualTo(0.20);
        assertThat(p.epsilon()).isEqualTo(0.10);
    }

    @Test
    void rejectsWeightsNotSummingToOne() {
        assertThatThrownBy(() -> new MatchingPolicy(0.50, 0.20, 0.20, 0.20, 0.10))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("sum to 1.0");
    }

    @Test
    void rejectsNegativeWeight() {
        assertThatThrownBy(() -> new MatchingPolicy(-0.10, 0.50, 0.30, 0.30, 0.10))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsEpsilonOutOfRange() {
        assertThatThrownBy(() -> new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 1.5))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("epsilon");
    }

    @Test
    void epsilonZeroIsValid() {
        assertThat(new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 0.0).epsilon()).isZero();
    }
}
