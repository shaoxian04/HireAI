package com.hireai.utility.result;

/**
 * Stable, machine-readable result codes returned in every {@code WebResult} and carried by
 * domain/application exceptions. HTTP status conveys the transport-level outcome; ResultCode
 * conveys the domain outcome.
 *
 * <p>Lives in the {@code utility} module — the bottom of the layer stack — because it is shared
 * by every layer (domain, application, repository, infrastructure, controller). Keeping it here
 * is what lets the layered modules depend only "downward".
 */
public enum ResultCode {

    SUCCESS("OK"),
    VALIDATION_ERROR("VALIDATION_ERROR"),
    NOT_FOUND("NOT_FOUND"),
    DOMAIN_RULE_VIOLATION("DOMAIN_RULE_VIOLATION"),
    INSUFFICIENT_BALANCE("INSUFFICIENT_BALANCE"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT"),
    SPEND_CAP_EXCEEDED("SPEND_CAP_EXCEEDED"),
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
