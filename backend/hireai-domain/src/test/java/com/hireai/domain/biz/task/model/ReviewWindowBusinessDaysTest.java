package com.hireai.domain.biz.task.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-Item Tasks count their review window in business days (spec S7/D10): only Monday to
 * Friday time (UTC) uses the window up, so a batch delivered on Friday afternoon is not
 * auto-accepted over the weekend.
 */
class ReviewWindowBusinessDaysTest {

    private final ReviewWindow day = new ReviewWindow(Duration.ofHours(24));

    @Test
    void aWindowOpenedOnATuesdayClosesTheSameTimeOnWednesday() {
        // 2026-11-17 is a Tuesday.
        assertThat(day.closesAtCountingBusinessDays(Instant.parse("2026-11-17T10:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-18T10:00:00Z"));
    }

    @Test
    void aWindowOpenedOnFridaySkipsTheWeekend() {
        // Friday 2026-11-20 15:00 → the 9h left on Friday, then 15h of Monday.
        assertThat(day.closesAtCountingBusinessDays(Instant.parse("2026-11-20T15:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-23T15:00:00Z"));
    }

    @Test
    void aWindowOpenedAtTheWeekendStartsCountingOnMonday() {
        // Saturday 2026-11-21 → counting starts Monday 00:00 and ends Tuesday 00:00.
        assertThat(day.closesAtCountingBusinessDays(Instant.parse("2026-11-21T09:30:00Z")))
                .isEqualTo(Instant.parse("2026-11-24T00:00:00Z"));
    }

    @Test
    void aLongWindowSpansSeveralWeekends() {
        // 72h from Thursday 2026-11-19 12:00: Thu 12h + Fri 24h + Mon 24h + Tue 12h.
        assertThat(new ReviewWindow(Duration.ofHours(72))
                .closesAtCountingBusinessDays(Instant.parse("2026-11-19T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-24T12:00:00Z"));
    }

    @Test
    void aShortWindowInsideOneWeekdayIsPlainClockTime() {
        assertThat(new ReviewWindow(Duration.ofMinutes(4))
                .closesAtCountingBusinessDays(Instant.parse("2026-11-18T23:58:00Z")))
                .isEqualTo(Instant.parse("2026-11-19T00:02:00Z"));
    }
}
