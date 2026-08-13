package com.hireai.controller.biz.agent.dto;

import com.hireai.application.biz.reputation.ReputationReadAppService;
import com.hireai.domain.biz.reputation.model.ReputationEventModel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The builder-facing reputation breakdown.
 *
 * <p>Components are exposed on the same 0–100 scale as the headline score so a builder can read
 * all three against one another without doing arithmetic. Each carries its {@code sampleCount},
 * because the count is the confidence: a reliability of 90 over 40 tasks is a far stronger claim
 * than the same figure over three.
 *
 * <p>{@code unproven} is explicit rather than inferred from a zero count, so the UI does not have
 * to re-derive the rule that an agent with no evidence reads as <em>unproven</em>, never as
 * excellent.
 */
public record AgentReputationDTO(BigDecimal score,
                                 Component reliability,
                                 Component satisfaction,
                                 boolean unproven,
                                 List<Event> recentEvents) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int DISPLAY_SCALE = 1;

    public record Component(BigDecimal value, long sampleCount) {
    }

    public record Event(UUID id, UUID taskId, String eventType, BigDecimal quality,
                        BigDecimal weight, Instant occurredAt) {

        static Event from(ReputationEventModel e) {
            return new Event(e.id(), e.taskId(), e.eventType().name(), e.quality(), e.weight(),
                    e.occurredAt());
        }
    }

    public static AgentReputationDTO from(ReputationReadAppService.Breakdown breakdown) {
        var s = breakdown.score();
        return new AgentReputationDTO(
                s.score(),
                new Component(onHundredScale(s.reliability()), s.reliabilityCount()),
                new Component(onHundredScale(s.satisfaction()), s.satisfactionCount()),
                s.isUnproven(),
                breakdown.recentEvents().stream().map(Event::from).toList());
    }

    private static BigDecimal onHundredScale(BigDecimal component) {
        return component.multiply(HUNDRED).setScale(DISPLAY_SCALE, java.math.RoundingMode.HALF_UP);
    }
}
