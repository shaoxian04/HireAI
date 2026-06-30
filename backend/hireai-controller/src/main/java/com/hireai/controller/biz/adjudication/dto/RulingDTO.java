package com.hireai.controller.biz.adjudication.dto;

import java.time.Instant;

public record RulingDTO(int tier, String decidedBy, String category, String rationale,
                        Instant decidedAt) {}
