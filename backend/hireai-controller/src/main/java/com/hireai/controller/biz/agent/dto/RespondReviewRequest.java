package com.hireai.controller.biz.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RespondReviewRequest(@NotBlank @Size(max = 2000) String response) {
}
