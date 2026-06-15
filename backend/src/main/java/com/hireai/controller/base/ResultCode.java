package com.hireai.controller.base;

/**
 * Stable, machine-readable result codes returned in every {@link WebResult}.
 * HTTP status conveys transport-level outcome; ResultCode conveys domain outcome.
 */
public enum ResultCode {

    SUCCESS("OK"),
    VALIDATION_ERROR("VALIDATION_ERROR"),
    NOT_FOUND("NOT_FOUND"),
    DOMAIN_RULE_VIOLATION("DOMAIN_RULE_VIOLATION"),
    INSUFFICIENT_BALANCE("INSUFFICIENT_BALANCE"),
    EMAIL_ALREADY_REGISTERED("EMAIL_ALREADY_REGISTERED"),
    INTERNAL_ERROR("INTERNAL_ERROR");

    private final String code;

    ResultCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
