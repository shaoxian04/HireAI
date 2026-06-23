package com.hireai.controller.biz.task.dto;

import jakarta.validation.constraints.Size;

/** Optional rejection context from the client. */
public record RejectTaskRequest(@Size(max = 500) String reason) {
}
