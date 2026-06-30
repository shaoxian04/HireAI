package com.hireai.controller.biz.adjudication;

/**
 * Thrown when the {@code category} field in an arbitration ruling callback does not match any
 * {@link com.hireai.domain.biz.adjudication.enums.RulingCategory} constant.
 * Handled locally in {@link ArbitrationCallbackController} and mapped to HTTP 400.
 */
public class InvalidRulingCategoryException extends RuntimeException {

    public InvalidRulingCategoryException(String badValue) {
        super(badValue);
    }
}
