package com.hireai.controller.biz.admin.dto;

import jakarta.validation.constraints.NotBlank;

/** Admin ruling body: a category (validated against RulingCategory in the controller) + a required rationale. */
public record AdminRuleRequest(@NotBlank String category, @NotBlank String rationale) {}
