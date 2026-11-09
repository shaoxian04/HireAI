package com.hireai.domain.biz.task.routing.service;

import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingPolicyTest {

    @Test
    void defaultsAreValidAndMatchTheSpec() {
        MatchingPolicy p = MatchingPolicy.defaults();
        assertThat(p.weightReputation()).isEqualTo(0.30);
        assertThat(p.weightRelevance()).isEqualTo(0.25);
        assertThat(p.weightValue()).isEqualTo(0.15);
        assertThat(p.weightLoad()).isEqualTo(0.15);
        assertThat(p.weightExploration()).isEqualTo(0.15);
        assertThat(p.epsilon()).isEqualTo(0.10);
        assertThat(p.relevanceFloor()).isEqualTo(0.25);
    }

    @Test
    void rejectsWeightsNotSummingToOne() {
        assertThatThrownBy(() -> new MatchingPolicy(0.30, 0.30, 0.15, 0.15, 0.15, 0.10, 0.25))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("sum to 1.0");
    }

    @Test
    void theRelevanceWeightCountsTowardsTheSum() {
        // The four pre-relevance weights alone sum to 1.0, so a non-zero relevance weight breaks it.
        assertThatThrownBy(() -> new MatchingPolicy(0.40, 0.10, 0.20, 0.20, 0.20, 0.10, 0.25))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("sum to 1.0");
    }

    @Test
    void rejectsNegativeWeight() {
        assertThatThrownBy(() -> new MatchingPolicy(-0.10, 0.25, 0.35, 0.25, 0.25, 0.10, 0.25))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsEpsilonOutOfRange() {
        assertThatThrownBy(() -> MatchingPolicy.defaults().withEpsilon(1.5))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("epsilon");
    }

    @Test
    void epsilonZeroIsValid() {
        assertThat(MatchingPolicy.defaults().withEpsilon(0.0).epsilon()).isZero();
    }

    @Test
    void rejectsARelevanceFloorOutsideTheCosineRange() {
        assertThatThrownBy(() -> new MatchingPolicy(0.30, 0.25, 0.15, 0.15, 0.15, 0.10, 1.5))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("relevance floor");
        assertThatThrownBy(() -> new MatchingPolicy(0.30, 0.25, 0.15, 0.15, 0.15, 0.10, Double.NaN))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("relevance floor");
    }

    @Test
    void theFloorIsInclusiveAndAMissingSimilarityPasses() {
        MatchingPolicy p = MatchingPolicy.defaults();
        assertThat(p.passesRelevanceFloor(0.25)).isTrue();
        assertThat(p.passesRelevanceFloor(0.2499)).isFalse();
        assertThat(p.passesRelevanceFloor(null)).isTrue();
    }
}
