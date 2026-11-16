package com.hireai.controller.biz.acceptance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A published Template version, as people and machine clients read it. */
public record TemplateDTO(
        String key,
        int version,
        String title,
        String description,
        String outputShape,
        List<String> checks,
        List<ParameterDTO> parameters,
        String expectationMode,
        /** Fraction of the price the Builder's side receives under each ruling category. */
        Map<String, BigDecimal> rulingSettlement,
        String status,
        Instant publishedAt,
        /** New Contracts use this version (spec D36): the newest ACTIVE one of its key. */
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean current,
        /** It is current, or the previous version during its transition; Offers may name only these. */
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean takesNewContracts,
        /** The previous version's transition end; absent for the current version and closed ones. */
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant takesNewContractsUntil
) {

    /** {@code comparison} is one of max, min, subset, enum, flag. */
    public record ParameterDTO(String name, String label, String description, String comparison,
                               BoundsDTO bounds) {
    }

    /** Only the fields the comparison uses are present. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BoundsDTO(BigDecimal minimum, BigDecimal maximum, List<String> allowedValues,
                            Integer maxLength) {
    }
}
