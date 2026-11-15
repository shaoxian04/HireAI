package com.hireai.domain.biz.acceptance.coverage.info;

import java.util.List;

/**
 * Whether one Offer covers one Contract (spec D34), and if not, why: the Offer names no term for the
 * Contract's Template version, or it does and these parameters fail their comparison (name order).
 */
public record Coverage(boolean templateOffered, List<String> blockingParameters) {

    private static final Coverage COVERED = new Coverage(true, List.of());
    private static final Coverage TEMPLATE_NOT_OFFERED = new Coverage(false, List.of());

    public Coverage {
        blockingParameters = List.copyOf(blockingParameters);
    }

    public static Coverage full() {
        return COVERED;
    }

    public static Coverage templateNotOffered() {
        return TEMPLATE_NOT_OFFERED;
    }

    public static Coverage blockedBy(List<String> parameters) {
        return parameters.isEmpty() ? COVERED : new Coverage(true, parameters);
    }

    public boolean covered() {
        return templateOffered && blockingParameters.isEmpty();
    }
}
