package com.hireai.controller.base;

/**
 * Common base for REST controllers. Provides response-wrapping helpers so that
 * concrete controllers stay thin: validate, call one app service, wrap result.
 *
 * Correlation-ID propagation and shared cross-cutting concerns will be added
 * here as the platform grows.
 */
public abstract class BaseController {

    protected <T> WebResult<T> ok(T data) {
        return WebResult.ok(data);
    }

    protected <T> WebResult<T> ok() {
        return WebResult.ok();
    }
}
