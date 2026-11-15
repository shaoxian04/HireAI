// DisputeModelTest.java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisputeModelTest {

    private static final Instant FIXED = Instant.parse("2026-07-01T00:00:00Z");

    private DisputeModel openDispute() {
        return DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(), RejectReason.A_MISMATCH, "corr-1");
    }

    @Test
    void openStartsInOpenWithNoRuling() {
        DisputeModel d = openDispute();
        assertThat(d.status()).isEqualTo(DisputeStatus.OPEN);
        assertThat(d.effectiveRuling()).isEmpty();
        assertThat(d.isResolvable()).isTrue();
        assertThat(d.reasonCategory()).isEqualTo(RejectReason.A_MISMATCH);
    }

    @Test
    void openRejectsChangedMindReason() {
        assertThatThrownBy(() ->
                DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(), RejectReason.D_CHANGED_MIND, "c"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rulingThenResolveDrivesToResolved() {
        Ruling ruling = new Ruling(1, RulingCategory.PARTIALLY_FULFILLED, "half done",
                RulingDecidedBy.ARBITRATOR, FIXED);
        DisputeModel resolved = openDispute().startArbitrating().recordRuling(ruling).resolve();
        assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.effectiveRuling()).contains(ruling);
        assertThat(resolved.isResolvable()).isFalse();
    }

    @Test
    void recordRulingAllowedDirectlyFromOpen() {
        Ruling ruling = new Ruling(1, RulingCategory.FULFILLED, "ok", RulingDecidedBy.ARBITRATOR, FIXED);
        DisputeModel ruled = openDispute().recordRuling(ruling);
        assertThat(ruled.status()).isEqualTo(DisputeStatus.RULED);
    }

    @Test
    void recordRulingRejectedOnceResolved() {
        Ruling ruling = new Ruling(1, RulingCategory.FULFILLED, "ok", RulingDecidedBy.ARBITRATOR, FIXED);
        DisputeModel resolved = openDispute().recordRuling(ruling).resolve();
        assertThatThrownBy(() -> resolved.recordRuling(ruling)).isInstanceOf(DomainException.class);
    }

    @Test
    void resolveByFallbackFromArbitratingGoesResolved() {
        Ruling fb = new Ruling(1, RulingCategory.NOT_FULFILLED, "arbitrator unavailable",
                RulingDecidedBy.FALLBACK, FIXED);
        DisputeModel resolved = openDispute().startArbitrating().resolveByFallback(fb);
        assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.effectiveRuling().get().decidedBy()).isEqualTo(RulingDecidedBy.FALLBACK);
    }

    @Test
    void rulingVoRejectsNullCategory() {
        assertThatThrownBy(() -> new Ruling(1, null, "x", RulingDecidedBy.ARBITRATOR, FIXED))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void recordRulingAppendsToHistoryAndEffectiveIsHighestTier() {
        DisputeModel d = DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(),
                RejectReason.C_INCOMPLETE, "corr-1").startArbitrating();
        Ruling tier1 = new Ruling(1, RulingCategory.PARTIALLY_FULFILLED, "half done",
                RulingDecidedBy.ARBITRATOR, FIXED);

        DisputeModel ruled = d.recordRuling(tier1);

        assertThat(ruled.status()).isEqualTo(DisputeStatus.RULED);
        assertThat(ruled.rulings()).containsExactly(tier1);
        assertThat(ruled.effectiveRuling()).contains(tier1);
    }

    @Test
    void fallbackAppendsRulingAndResolves() {
        DisputeModel d = DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(),
                RejectReason.A_MISMATCH, "corr-2").startArbitrating();
        Ruling fb = new Ruling(1, RulingCategory.NOT_FULFILLED, "platform fallback",
                RulingDecidedBy.FALLBACK, FIXED);

        DisputeModel resolved = d.resolveByFallback(fb);

        assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.effectiveRuling()).contains(fb);
        assertThat(resolved.resolvedAt()).isNotNull();
    }

    @Test
    void escalateFromArbitratingSignalsNeedsAdmin() {
        DisputeModel d = openDispute().startArbitrating().escalate();
        assertThat(d.status()).isEqualTo(DisputeStatus.ESCALATED);
        // isResolvable() gates the ARBITRATOR callback; an escalated dispute must be excluded so a
        // late arbitrator ruling is ignored and the admin owns it.
        assertThat(d.isResolvable()).isFalse();
    }

    @Test
    void escalateFromOpenIsAllowed() {
        assertThat(openDispute().escalate().status()).isEqualTo(DisputeStatus.ESCALATED);
    }

    @Test
    void escalateRejectedOnceResolved() {
        DisputeModel resolved = openDispute()
                .recordRuling(new Ruling(1, RulingCategory.FULFILLED, "ok", RulingDecidedBy.ARBITRATOR, FIXED))
                .resolve();
        assertThatThrownBy(resolved::escalate).isInstanceOf(DomainException.class);
    }

    @Test
    void adminCanRuleAnEscalatedDisputeAtTierTwo() {
        Ruling admin = new Ruling(2, RulingCategory.NOT_FULFILLED, "backstop refund",
                RulingDecidedBy.ADMINISTRATOR, FIXED);
        DisputeModel resolved = openDispute().startArbitrating().escalate().recordRuling(admin).resolve();
        assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.effectiveRuling().get().decidedBy()).isEqualTo(RulingDecidedBy.ADMINISTRATOR);
        assertThat(resolved.effectiveRuling().get().tier()).isEqualTo(2);
    }

    private static DisputeModel ruledAs(RulingCategory category) {
        return DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(), RejectReason.B_FACTUAL, "corr")
                .recordRuling(new Ruling(1, category, "r", RulingDecidedBy.ARBITRATOR, FIXED));
    }

    @Test
    void theSideARulingGoesAgainstIsTheOneThatMayAppealIt() {
        assertThat(ruledAs(RulingCategory.FULFILLED).partiesWhoMayAppeal()).containsExactly(DisputeParty.CLIENT);
        assertThat(ruledAs(RulingCategory.NOT_FULFILLED).partiesWhoMayAppeal()).containsExactly(DisputeParty.BUILDER);
        assertThat(ruledAs(RulingCategory.PARTIALLY_FULFILLED).partiesWhoMayAppeal())
                .containsExactlyInAnyOrder(DisputeParty.CLIENT, DisputeParty.BUILDER);
    }

    @Test
    void appealByTheLosingSideEscalatesAndRecordsWhoAppealed() {
        DisputeModel appealed = ruledAs(RulingCategory.NOT_FULFILLED).appeal(DisputeParty.BUILDER, FIXED);

        assertThat(appealed.status()).isEqualTo(DisputeStatus.ESCALATED);
        assertThat(appealed.appealedBy()).isEqualTo(DisputeParty.BUILDER);
        assertThat(appealed.appealedAt()).isEqualTo(FIXED);
        assertThat(appealed.rulings()).hasSize(1); // arbitrator proposal preserved as admin context
    }

    @Test
    void theSideARulingFavoursCannotAppealIt() {
        assertThatThrownBy(() -> ruledAs(RulingCategory.NOT_FULFILLED).appeal(DisputeParty.CLIENT, FIXED))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("in your favour");
    }

    @Test
    void appeal_fromNonRuled_throws() {
        DisputeModel open = DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(), RejectReason.A_MISMATCH, "corr");
        assertThatThrownBy(() -> open.appeal(DisputeParty.CLIENT, FIXED))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("appeal requires RULED");
    }

    @Test
    void aRulingIsFinalOnlyOnceEverySideThatMayAppealItHasAccepted() {
        DisputeModel ruled = ruledAs(RulingCategory.PARTIALLY_FULFILLED);

        DisputeModel clientAccepted = ruled.accept(DisputeParty.CLIENT, FIXED);
        assertThat(clientAccepted.clientAcceptedAt()).isEqualTo(FIXED);
        assertThat(clientAccepted.acceptedByEverySideThatMayAppeal()).isFalse();
        assertThat(clientAccepted.status()).isEqualTo(DisputeStatus.RULED);

        DisputeModel both = clientAccepted.accept(DisputeParty.BUILDER, FIXED.plusSeconds(5));
        assertThat(both.builderAcceptedAt()).isEqualTo(FIXED.plusSeconds(5));
        assertThat(both.acceptedByEverySideThatMayAppeal()).isTrue();
    }

    @Test
    void aSideCannotAppealAfterAcceptingNorAcceptTwice() {
        DisputeModel accepted = ruledAs(RulingCategory.PARTIALLY_FULFILLED).accept(DisputeParty.CLIENT, FIXED);

        assertThatThrownBy(() -> accepted.appeal(DisputeParty.CLIENT, FIXED))
                .isInstanceOf(DomainException.class).hasMessageContaining("already accepted");
        assertThatThrownBy(() -> accepted.accept(DisputeParty.CLIENT, FIXED))
                .isInstanceOf(DomainException.class).hasMessageContaining("already accepted");
        // The other side keeps its own right to appeal.
        assertThat(accepted.appeal(DisputeParty.BUILDER, FIXED).status()).isEqualTo(DisputeStatus.ESCALATED);
    }

    @Test
    void theFavouredSideHasNothingToAccept() {
        assertThatThrownBy(() -> ruledAs(RulingCategory.FULFILLED).accept(DisputeParty.BUILDER, FIXED))
                .isInstanceOf(DomainException.class).hasMessageContaining("in your favour");
    }

    @Test
    void aRulingCanBeAppealedOnlyOnce() {
        DisputeModel appealed = ruledAs(RulingCategory.PARTIALLY_FULFILLED).appeal(DisputeParty.CLIENT, FIXED);
        DisputeModel adminRuled = appealed.recordRuling(
                new Ruling(2, RulingCategory.FULFILLED, "admin", RulingDecidedBy.ADMINISTRATOR, FIXED));

        assertThatThrownBy(() -> adminRuled.appeal(DisputeParty.CLIENT, FIXED))
                .isInstanceOf(DomainException.class).hasMessageContaining("once");
        assertThat(adminRuled.partiesWhoMayAppeal()).isEmpty();
    }
}
