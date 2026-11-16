package com.hireai.domain.biz.acceptance.engagement;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.engagement.enums.EngagementStatus;
import com.hireai.domain.biz.acceptance.engagement.model.EngagementModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An Engagement (D44, ADR 0010): the buyer's consent, an optional Template variant, its retention. */
class EngagementModelTest {

    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private final UUID client = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final Contract variant = new Contract(new TemplateRef("text_basic", 1), Map.of());

    @Test
    void withoutAVariantItIsActiveAtOnceAndKeepsDocumentsThirtyDaysByDefault() {
        EngagementModel e = EngagementModel.open(client, agent, true, null, null, NOW);

        assertThat(e.status()).isEqualTo(EngagementStatus.ACTIVE);
        assertThat(e.consentedAt()).isEqualTo(NOW);
        assertThat(e.retentionDays()).isEqualTo(30);
        e.requireActive();
        assertThat(e.isOwnedBy(client)).isTrue();
        assertThat(e.isOwnedBy(agent)).isFalse();
    }

    @Test
    void itNeedsTheBuyersConsentAndARetentionOfOneToThirtyDays() {
        assertThatThrownBy(() -> EngagementModel.open(client, agent, false, null, null, NOW))
                .isInstanceOf(DomainException.class).hasMessageContaining("confidentialConsent");
        assertThatThrownBy(() -> EngagementModel.open(client, agent, true, null, 31, NOW))
                .isInstanceOf(DomainException.class).hasMessageContaining("retentionDays");
        assertThatThrownBy(() -> EngagementModel.open(client, agent, true, null, 0, NOW))
                .isInstanceOf(DomainException.class).hasMessageContaining("retentionDays");
        assertThat(EngagementModel.open(client, agent, true, null, 7, NOW).retentionDays()).isEqualTo(7);
    }

    @Test
    void aVariantWaitsForTheAgentAndOnceAcceptedCoversExactlyThatContract() {
        EngagementModel waiting = EngagementModel.open(client, agent, true, variant, null, NOW);
        assertThat(waiting.status()).isEqualTo(EngagementStatus.AWAITING_AGENT_ACCEPTANCE);
        assertThatThrownBy(waiting::requireActive).isInstanceOf(DomainException.class).hasMessageContaining("waiting");
        assertThat(waiting.agentAccepted(variant)).isFalse();

        EngagementModel accepted = waiting.acceptVariant(NOW.plusSeconds(60));
        assertThat(accepted.status()).isEqualTo(EngagementStatus.ACTIVE);
        assertThat(accepted.agentAcceptedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(accepted.agentAccepted(new Contract(new TemplateRef("text_basic", 1), Map.of()))).isTrue();
        assertThat(accepted.agentAccepted(new Contract(new TemplateRef("text_basic", 1),
                Map.of("max_chars", new ParameterValue.Number(new java.math.BigDecimal("10")))))).isFalse();
        assertThatThrownBy(() -> accepted.acceptVariant(NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void anEndedEngagementTakesNothingNewAndEndsOnce() {
        EngagementModel ended = EngagementModel.open(client, agent, true, null, null, NOW).end(NOW);

        assertThat(ended.status()).isEqualTo(EngagementStatus.ENDED);
        assertThatThrownBy(ended::requireActive).isInstanceOf(DomainException.class).hasMessageContaining("ended");
        assertThatThrownBy(() -> ended.end(NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void aBookingUnderItMustNameItsAgent() {
        EngagementModel e = EngagementModel.open(client, agent, true, null, null, NOW);
        e.requireAgent(agent);
        assertThatThrownBy(() -> e.requireAgent(UUID.randomUUID()))
                .isInstanceOf(DomainException.class).hasMessageContaining("another Agent");
    }
}
