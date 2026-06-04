package com.hireai.domain.shared.exception;

import com.hireai.controller.base.ResultCode;

/**
 * Raised by the domain layer when a business invariant would be violated.
 *
 * Note: this is the single intentional dependency from the domain onto a
 * controller-layer enum ({@link ResultCode}) so that the global exception
 * handler can map a domain failure to a stable API code without a translation
 * table. It carries no framework imports. If stricter purity is required later,
 * ResultCode can be moved to a shared module.
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
