// NetworkntSchemaValidatorTest.java
package com.hireai.infrastructure.adjudication;

import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NetworkntSchemaValidatorTest {

    private final NetworkntSchemaValidator validator = new NetworkntSchemaValidator();

    @Test
    void invalidJsonIsReported() {
        JsonCheckResult r = validator.check("{not json", null);
        assertThat(r.validJson()).isFalse();
    }

    @Test
    void validJsonNoSchemaIsApplicableFalse() {
        JsonCheckResult r = validator.check("{\"a\":1}", null);
        assertThat(r.validJson()).isTrue();
        assertThat(r.schemaApplicable()).isFalse();
    }

    @Test
    void freeProseSchemaIsNotApplicable() {
        JsonCheckResult r = validator.check("{\"a\":1}", "must be a summary under 200 words");
        assertThat(r.validJson()).isTrue();
        assertThat(r.schemaApplicable()).isFalse();
    }

    @Test
    void jsonSchemaMatchAndMismatch() {
        String schema = "{\"type\":\"object\",\"required\":[\"title\"],\"properties\":{\"title\":{\"type\":\"string\"}}}";
        assertThat(validator.check("{\"title\":\"hi\"}", schema).schemaMatches()).isTrue();
        JsonCheckResult miss = validator.check("{\"x\":1}", schema);
        assertThat(miss.schemaApplicable()).isTrue();
        assertThat(miss.schemaMatches()).isFalse();
    }
}
