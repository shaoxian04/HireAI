package com.hireai.controller.biz.acceptance.dto;

import com.hireai.controller.biz.task.dto.SubmitTaskRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound DTO for {@code POST /api/contracts/draft}: the task text to extract, and which Template to
 * extract onto: {@code template} (a key; its current version is used), else the Template
 * {@code outputSpec} maps onto (with its schema), else {@code text_basic}. Give one of the two at most.
 */
public record ContractDraftRequest(
        @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @Size(max = 64) String template,
        @Valid SubmitTaskRequest.OutputSpecRequest outputSpec
) {
}
