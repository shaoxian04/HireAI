package com.hireai.controller.config;

import com.hireai.application.biz.auth.AuthenticationFailedException;
import com.hireai.controller.base.ResultCode;
import com.hireai.controller.base.WebResult;
import com.hireai.domain.shared.exception.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates exceptions into the unified {@link WebResult} envelope with an
 * appropriate HTTP status. Keeps controllers free of try/catch boilerplate.
 */
@RestControllerAdvice
public class GlobalExceptionConfiguration {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<WebResult<Void>> handleDomain(DomainException ex) {
        HttpStatus status = switch (ex.resultCode()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            // Genuine state conflicts → 409. VALIDATION_ERROR is bad input, so it falls through
            // to the default 400 Bad Request.
            case INSUFFICIENT_BALANCE, DOMAIN_RULE_VIOLATION -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(WebResult.error(ex.resultCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<WebResult<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(WebResult.error(ResultCode.VALIDATION_ERROR,
                        "Invalid value for parameter '" + ex.getName() + "'"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<WebResult<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(WebResult.error(ResultCode.VALIDATION_ERROR, message));
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<WebResult<Void>> handleAuthFailure(AuthenticationFailedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(WebResult.error(ResultCode.VALIDATION_ERROR, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<WebResult<Void>> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(WebResult.error(ResultCode.INTERNAL_ERROR, "Unexpected error"));
    }
}
