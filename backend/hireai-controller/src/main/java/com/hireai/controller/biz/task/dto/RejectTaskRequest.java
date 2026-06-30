package com.hireai.controller.biz.task.dto;

import com.hireai.domain.biz.task.enums.RejectReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Required rejection category + optional free-text reason from the client. */
public record RejectTaskRequest(@NotNull RejectReason reasonCategory, @Size(max = 500) String reason) {
}
