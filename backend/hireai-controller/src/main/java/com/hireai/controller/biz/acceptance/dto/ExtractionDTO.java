package com.hireai.controller.biz.acceptance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * What requirement extraction made of a description (spec S6): the Template version the requirements
 * were mapped against, {@code status} EXTRACTED or UNAVAILABLE (the LLM could not answer, so the
 * Template's defaults were used and every requirement stays in the description), and each requirement.
 */
public record ExtractionDTO(String template, int version, String status, List<RequirementDTO> requirements) {

    /**
     * One requirement: {@code mapped} with the Check Library entry ({@code check}) and the Contract
     * parameters it set, or not mapped, with {@code unmappedReason}; an unmapped requirement is not
     * checked automatically (it is left to your review, AI Review or arbitration evidence).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequirementDTO(String text, boolean mapped, String check, Map<String, Object> params,
                                 String unmappedReason) {
    }
}
