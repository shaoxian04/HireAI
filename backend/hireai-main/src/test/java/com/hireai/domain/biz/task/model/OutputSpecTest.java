package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputSpecTest {

    @Test
    void buildsWithFormatAndOptionalFields() {
        OutputSpec spec = new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "must be valid JSON");
        assertThat(spec.format()).isEqualTo(OutputFormat.JSON);
        assertThat(spec.schema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(spec.acceptanceCriteria()).isEqualTo("must be valid JSON");
    }

    @Test
    void rejectsNullFormat() {
        assertThatThrownBy(() -> new OutputSpec(null, null, null))
                .isInstanceOf(DomainException.class);
    }
}
