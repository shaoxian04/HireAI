package com.hireai.domain.biz.task.sampling;

import com.hireai.domain.biz.task.sampling.model.SampleDraw;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The sample is drawn from a recorded seed (spec D6): anyone holding the seed can redraw it. */
class SampleDrawTest {

    private static final List<Integer> LOT = IntStream.rangeClosed(1, 500).boxed().toList();

    @Test
    void theSameSeedAlwaysDrawsTheSameItems() {
        assertThat(SampleDraw.draw(42L, LOT, 32)).isEqualTo(SampleDraw.draw(42L, LOT, 32));
    }

    @Test
    void theDrawIsAPublishedAlgorithmAnAuditorCanRepeat() {
        // Partial Fisher-Yates over the ascending Item numbers with java.util.Random(seed), sorted.
        // This literal pins the algorithm: changing it would make recorded seeds redraw differently.
        assertThat(SampleDraw.draw(7L, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 3)).isEqualTo(List.of(4, 7, 8));
    }

    @Test
    void aDrawHoldsDistinctItemsOfTheLotInItemOrder() {
        List<Integer> sample = SampleDraw.draw(123456789L, LOT, 50);
        assertThat(sample).hasSize(50).isSorted();
        assertThat(new HashSet<>(sample)).hasSize(50);
        assertThat(LOT).containsAll(sample);
    }

    @Test
    void differentSeedsDrawDifferentSamples() {
        assertThat(SampleDraw.draw(1L, LOT, 32)).isNotEqualTo(SampleDraw.draw(2L, LOT, 32));
    }

    @Test
    void theCandidatesOrderDoesNotChangeTheDraw() {
        List<Integer> shuffled = new java.util.ArrayList<>(LOT);
        java.util.Collections.reverse(shuffled);
        assertThat(SampleDraw.draw(99L, shuffled, 20)).isEqualTo(SampleDraw.draw(99L, LOT, 20));
    }

    @Test
    void askingForMoreThanTheLotTakesTheWholeLot() {
        assertThat(SampleDraw.draw(5L, List.of(4, 9, 2), 10)).containsExactly(2, 4, 9);
    }
}
