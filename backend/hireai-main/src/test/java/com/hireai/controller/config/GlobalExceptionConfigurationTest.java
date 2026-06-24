package com.hireai.controller.config;

import com.hireai.utility.result.ResultCode;
import com.hireai.controller.base.WebResult;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the DomainException -> HTTP status mapping. A VALIDATION_ERROR is bad input and must
 * map to 400 Bad Request (not 409 Conflict, which is reserved for genuine state conflicts such as
 * an insufficient balance or an illegal state transition).
 */
class GlobalExceptionConfigurationTest {

    private final GlobalExceptionConfiguration advice = new GlobalExceptionConfiguration();

    @Test
    void validationErrorMapsToBadRequest() {
        ResponseEntity<WebResult<Void>> response =
                advice.handleDomain(new DomainException(ResultCode.VALIDATION_ERROR, "Webhook URL must be HTTPS"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void notFoundMapsToNotFound() {
        ResponseEntity<WebResult<Void>> response =
                advice.handleDomain(new DomainException(ResultCode.NOT_FOUND, "missing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void stateConflictsMapToConflict() {
        ResponseEntity<WebResult<Void>> insufficient =
                advice.handleDomain(new DomainException(ResultCode.INSUFFICIENT_BALANCE, "no funds"));
        ResponseEntity<WebResult<Void>> ruleViolation =
                advice.handleDomain(new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "illegal transition"));

        assertThat(insufficient.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ruleViolation.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
