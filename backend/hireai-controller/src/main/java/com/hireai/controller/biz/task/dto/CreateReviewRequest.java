package com.hireai.controller.biz.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A client's star rating of a task they accepted, with optional prose. */
public record CreateReviewRequest(@NotNull @Min(1) @Max(5) Integer rating,
                                  @Size(max = 2000) String reviewText) {
}
