package com.hireai.controller.base;

import com.hireai.utility.result.ResultCode;

/**
 * Unified response envelope for every endpoint. Controllers never return a bare
 * DTO; they wrap it here so success/error shape is consistent across the API.
 *
 * @param <T> payload type
 */
public record WebResult<T>(boolean success, String code, String message, T data) {

    public static <T> WebResult<T> ok(T data) {
        return new WebResult<>(true, ResultCode.SUCCESS.code(), null, data);
    }

    public static <T> WebResult<T> ok() {
        return new WebResult<>(true, ResultCode.SUCCESS.code(), null, null);
    }

    public static <T> WebResult<T> error(ResultCode code, String message) {
        return new WebResult<>(false, code.code(), message, null);
    }
}
