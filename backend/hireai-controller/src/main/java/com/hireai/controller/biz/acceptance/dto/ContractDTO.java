package com.hireai.controller.biz.acceptance.dto;

import java.util.Map;

/**
 * A Task's frozen Contract: the Template version it is judged under and the parameter values.
 * Each value is natural JSON: a number ({@code max}/{@code min}), a string ({@code enum}), an array
 * of strings ({@code subset}) or a boolean ({@code flag}). The Template's labels and comparisons are
 * served by {@code GET /api/catalogue/templates/{key}/{version}}.
 */
public record ContractDTO(String template, int version, Map<String, Object> params) {
}
