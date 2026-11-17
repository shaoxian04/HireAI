package com.hireai.domain.biz.task.dispatchattempt;

import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import com.hireai.domain.biz.task.dispatchattempt.info.ContractDeclineRate;
import com.hireai.domain.biz.task.dispatchattempt.model.DispatchAttemptModel;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractDeclineTest {

    private static final UUID TASK = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");

    private DispatchAttemptModel declined(String reason) {
        return DispatchAttemptModel.contractDeclined(TASK, VERSION, 0, reason, NOW);
    }

    @Test
    void aContractDeclineKeepsTheAgentsReasonTrimmed() {
        DispatchAttemptModel attempt = declined("  I only summarise English text \n");

        assertThat(attempt.outcome()).isEqualTo(DispatchOutcome.CONTRACT_DECLINED);
        assertThat(attempt.attemptNumber()).isEqualTo(1);
        assertThat(attempt.declineReason()).isEqualTo("I only summarise English text");
    }

    @Test
    void aBlankOrMissingReasonIsNoReason() {
        assertThat(declined(null).declineReason()).isNull();
        assertThat(declined("   ").declineReason()).isNull();
    }

    @Test
    void controlCharactersBecomeSpacesAndALongReasonIsCutTo500Characters() {
        assertThat(declined("line one\nline\u0000two\ttab").declineReason()).isEqualTo("line one line two tab");
        assertThat(declined("x".repeat(2_000)).declineReason()).hasSize(500);
    }

    @Test
    void otherOutcomesCarryNoReason() {
        assertThat(DispatchAttemptModel.next(TASK, VERSION, 0, DispatchOutcome.BUSY, NOW).declineReason()).isNull();
        assertThatThrownBy(() -> DispatchAttemptModel.next(TASK, VERSION, 0, DispatchOutcome.CONTRACT_DECLINED, NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void theRateIsDeclinesAmongTheWorkOrdersWhoseContractTheAgentAnswered() {
        ContractDeclineRate rate = ContractDeclineRate.fromOutcomeCounts(Map.of(
                DispatchOutcome.CONTRACT_DECLINED, 1L, DispatchOutcome.ACCEPTED, 3L,
                DispatchOutcome.BUSY, 7L, DispatchOutcome.UNREACHABLE, 2L, DispatchOutcome.UNAVAILABLE, 1L));

        assertThat(rate.declined()).isEqualTo(1);
        assertThat(rate.answered()).isEqualTo(4);
        assertThat(rate.rate()).isEqualTo(0.25);
    }

    @Test
    void anAgentThatAnsweredNothingHasNoRateRatherThanZero() {
        ContractDeclineRate none = ContractDeclineRate.fromOutcomeCounts(Map.of(DispatchOutcome.BUSY, 2L));

        assertThat(none.answered()).isZero();
        assertThat(none.rate()).isNull();
        assertThat(ContractDeclineRate.NONE.rate()).isNull();
    }
}
