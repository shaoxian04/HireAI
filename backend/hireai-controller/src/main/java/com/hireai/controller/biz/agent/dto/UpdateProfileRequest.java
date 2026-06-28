package com.hireai.controller.biz.agent.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(@Size(max = 160) String tagline,
                                   @Size(max = 8000) String description,
                                   @Size(max = 8000) String sampleOutput,
                                   boolean isListed) {
}
