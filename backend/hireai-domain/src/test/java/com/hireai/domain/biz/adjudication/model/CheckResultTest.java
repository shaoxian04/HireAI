package com.hireai.domain.biz.adjudication.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.hireai.utility.exception.DomainException;

class CheckResultTest {
    @Test
    void holdsFields() {
        CheckResult c = new CheckResult("FORMAT_TEXT", true, "non-empty");
        assertThat(c.rule()).isEqualTo("FORMAT_TEXT");
        assertThat(c.passed()).isTrue();
        assertThat(c.detail()).isEqualTo("non-empty");
    }

    @Test
    void rejectsBlankRule() {
        assertThatThrownBy(() -> new CheckResult("  ", true, "x"))
            .isInstanceOf(DomainException.class);
    }
}
