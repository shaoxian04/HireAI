package com.hireai.utility.exception;

import com.hireai.utility.result.ResultCode;

/**
 * Raised when a business invariant would be violated. Carries a stable {@link ResultCode} so the
 * global exception handler can map the failure to an API code without a translation table.
 *
 * <p>Lives in the utility module (alongside {@link ResultCode}) so every layer — domain,
 * application, repository, infrastructure, controller — can throw it without a cross-layer
 * dependency.
 */
public class DomainException extends RuntimeException {

    private final ResultCode resultCode;

    public DomainException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode resultCode() {
        return resultCode;
    }
}
