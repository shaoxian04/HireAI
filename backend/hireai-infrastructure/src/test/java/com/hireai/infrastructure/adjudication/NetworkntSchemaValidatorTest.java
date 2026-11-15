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
    void aJsonSchemaObjectHasNoProblem() {
        assertThat(validator.problemWith("{}")).isEmpty();
        assertThat(validator.problemWith(
                "{\"type\":\"object\",\"required\":[\"total\"],\"properties\":{\"total\":{\"type\":\"number\"}}}"))
                .isEmpty();
    }

    @Test
    void proseIsNotJson() {
        assertThat(validator.problemWith("must be a summary under 200 words")).get().asString()
                .contains("not JSON");
    }

    @Test
    void jsonThatIsNotAnObjectIsNotASchemaObject() {
        assertThat(validator.problemWith("[1,2]")).get().asString().contains("not a JSON object");
        assertThat(validator.problemWith("true")).get().asString().contains("not a JSON object");
    }

    @Test
    void anObjectBreakingTheMetaSchemaIsNotAValidSchema() {
        assertThat(validator.problemWith("{\"type\":\"objekt\"}")).isPresent();
        assertThat(validator.problemWith("{\"required\":\"total\"}")).isPresent();
        assertThat(validator.problemWith("{\"minLength\":-1}")).isPresent();
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
