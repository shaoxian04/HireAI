package com.hireai.domain.biz.task.sampling.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Draws a sample from a recorded seed (spec D6). The seed is generated after delivery and kept with
 * the draw (and in the receipt), so the Agent cannot know which Items will be checked, and anyone
 * holding the seed can redraw the same sample: a partial Fisher-Yates shuffle of the candidate Item
 * numbers in ascending order, driven by {@code java.util.Random(seed)} (whose algorithm the Java SE
 * specification fixes), taking the first {@code size} and returning them in Item order.
 */
public final class SampleDraw {

    private SampleDraw() {
    }

    public static List<Integer> draw(long seed, Collection<Integer> candidates, int size) {
        List<Integer> pool = new ArrayList<>(candidates);
        Collections.sort(pool);
        int take = Math.min(size, pool.size());
        Random random = new Random(seed);
        for (int i = 0; i < take; i++) {
            int j = i + random.nextInt(pool.size() - i);
            Collections.swap(pool, i, j);
        }
        List<Integer> sample = new ArrayList<>(pool.subList(0, take));
        Collections.sort(sample);
        return List.copyOf(sample);
    }
}
