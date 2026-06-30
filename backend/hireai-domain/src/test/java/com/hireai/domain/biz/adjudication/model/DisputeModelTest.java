// DisputeModelTest.java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisputeModelTest {

    private DisputeModel openDispute() {
        return DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(), RejectReason.A_MISMATCH, "corr-1");
    }

    @Test
    void openStartsInOpenWithNoRuling() {
        DisputeModel d = openDispute();
        assertThat(d.status()).isEqualTo(DisputeStatus.OPEN);
        assertThat(d.ruling()).isNull();
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
        Ruling ruling = new Ruling(1, RulingCategory.PARTIALLY_FULFILLED, "half done", RulingDecidedBy.ARBITRATOR);
        DisputeModel resolved = openDispute().startArbitrating().recordRuling(ruling).resolve();
        assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.ruling()).isEqualTo(ruling);
        assertThat(resolved.isResolvable()).isFalse();
    }

    @Test
    void recordRulingAllowedDirectlyFromOpen() {
        Ruling ruling = new Ruling(1, RulingCategory.FULFILLED, "ok", RulingDecidedBy.ARBITRATOR);
        DisputeModel ruled = openDispute().recordRuling(ruling);
        assertThat(ruled.status()).isEqualTo(DisputeStatus.RULED);
    }

    @Test
    void recordRulingRejectedOnceResolved() {
        Ruling ruling = new Ruling(1, RulingCategory.FULFILLED, "ok", RulingDecidedBy.ARBITRATOR);
        DisputeModel resolved = openDispute().recordRuling(ruling).resolve();
        assertThatThrownBy(() -> resolved.recordRuling(ruling)).isInstanceOf(DomainException.class);
    }

    @Test
    void resolveByFallbackFromArbitratingGoesResolved() {
        Ruling fb = new Ruling(1, RulingCategory.NOT_FULFILLED, "arbitrator unavailable", RulingDecidedBy.FALLBACK);
        DisputeModel resolved = openDispute().startArbitrating().resolveByFallback(fb);
        assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.ruling().decidedBy()).isEqualTo(RulingDecidedBy.FALLBACK);
    }

    @Test
    void rulingVoRejectsNullCategory() {
        assertThatThrownBy(() -> new Ruling(1, null, "x", RulingDecidedBy.ARBITRATOR))
                .isInstanceOf(DomainException.class);
    }
}
