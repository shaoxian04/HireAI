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
    private static final int SHARE_SCALE = 3;

    /**
     * @param value       the component on the 0–100 scale
     * @param sampleCount how many samples stand behind it
     * @param priorWeight the share of {@code value} that is still the neutral starting point —
     *                    1.000 at zero samples, falling as evidence lands. Sent so the UI can say
     *                    "we do not know yet" without hardcoding kR/kS.
     * @param weight      this component's share of the blended score (α or 1−α)
     */
    public record Component(BigDecimal value, long sampleCount, BigDecimal priorWeight,
                            BigDecimal weight) {
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
        BigDecimal satisfactionWeight = BigDecimal.ONE.subtract(s.alpha());
        return new AgentReputationDTO(
                s.score(),
                new Component(onHundredScale(s.reliability()), s.reliabilityCount(),
                        share(s.reliabilityPriorWeight()), share(s.alpha())),
                new Component(onHundredScale(s.satisfaction()), s.satisfactionCount(),
                        share(s.satisfactionPriorWeight()), share(satisfactionWeight)),
                s.isUnproven(),
                breakdown.recentEvents().stream().map(Event::from).toList());
    }

    private static BigDecimal onHundredScale(BigDecimal component) {
        return component.multiply(HUNDRED).setScale(DISPLAY_SCALE, java.math.RoundingMode.HALF_UP);
    }

    /** A [0,1] share, trimmed to the 3dp a UI can meaningfully render as a percentage. */
    private static BigDecimal share(BigDecimal fraction) {
        return fraction.setScale(SHARE_SCALE, java.math.RoundingMode.HALF_UP);
    }
}
