package com.hireai.controller.biz.task.dto;

import com.hireai.domain.biz.task.enums.RejectReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The buyer's reviewer judges one Item (spec S8): {@code passed} true accepts and pays it; false rejects
 * it for {@code reasonCategory} A/B/C with {@code reason} (what is wrong), which the Agent answers.
 */
public record JudgeItemRequest(
        @NotNull Boolean passed,
        RejectReason reasonCategory,
        @Size(max = 500) String reason
) {
}
