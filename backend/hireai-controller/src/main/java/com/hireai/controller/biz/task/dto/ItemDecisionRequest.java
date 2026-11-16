package com.hireai.controller.biz.task.dto;

import com.hireai.domain.biz.task.rejection.enums.ClientDecision;
import jakarta.validation.constraints.NotNull;

/** The buyer's decision on an Item the Agent stood by after a reviewer rejected it (spec D17′). */
public record ItemDecisionRequest(@NotNull ClientDecision decision) {
}
