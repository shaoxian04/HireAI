package com.hireai.domain.biz.task.sampling.model;

import com.hireai.domain.biz.task.sampling.enums.InspectionLevel;
import com.hireai.domain.biz.task.sampling.enums.InspectionStatus;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The sampling inspection of one multi-Item Task (spec S8): the inspection level the buyer chose, the
 * round (1, or 2 after the whole-Task rework) and how it ended. Immutable; an illegal transition throws.
 * {@code errorRate} is set once it settled pro rata.
 */
public record InspectionModel(UUID taskId, InspectionLevel level, int round, InspectionStatus status,
                              BigDecimal errorRate) {

    public static InspectionModel open(UUID taskId, InspectionLevel level) {
        if (taskId == null || level == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "An inspection needs a task and a level");
        }
        return new InspectionModel(taskId, level, 1, InspectionStatus.SAMPLING, null);
    }

    /** SAMPLING (round 1) → REWORKING: the first round's sample failed. */
    public InspectionModel startRework() {
        require(InspectionStatus.SAMPLING, "startRework");
        if (round >= SamplingDecision.LAST_ROUND) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Task " + taskId + " was already reworked once after a failed sample");
        }
        return new InspectionModel(taskId, level, round, InspectionStatus.REWORKING, null);
    }

    /** REWORKING → SAMPLING (round 2): the reworked Items are back. */
    public InspectionModel resample() {
        require(InspectionStatus.REWORKING, "resample");
        return new InspectionModel(taskId, level, round + 1, InspectionStatus.SAMPLING, null);
    }

    public InspectionModel acceptLot() {
        require(InspectionStatus.SAMPLING, "acceptLot");
        return new InspectionModel(taskId, level, round, InspectionStatus.ACCEPTED, null);
    }

    public InspectionModel settleProRata(BigDecimal rate) {
        require(InspectionStatus.SAMPLING, "settleProRata");
        return new InspectionModel(taskId, level, round, InspectionStatus.PRO_RATA, rate);
    }

    /** The buyer accepted everything awaiting review before the sample decided (no-op once finished). */
    public InspectionModel waive() {
        return status.isFinished() ? this : new InspectionModel(taskId, level, round, InspectionStatus.WAIVED, null);
    }

    /**
     * The inspection ends with nothing left to sample (every Item failed its checks, was refunded or settled
     * on its own path, or the Task ended): no reviewer page is waiting. No-op once finished.
     */
    public InspectionModel close() {
        return status.isFinished() ? this : new InspectionModel(taskId, level, round, InspectionStatus.CLOSED, null);
    }

    public boolean isSampling() {
        return status == InspectionStatus.SAMPLING;
    }

    private void require(InspectionStatus expected, String transition) {
        if (status != expected) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "Task " + taskId + ": illegal inspection "
                    + "transition " + transition + " from " + status + "; expected " + expected);
        }
    }
}
