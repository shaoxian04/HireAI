package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ValidationReportModelTest {
    private final UUID taskId = UUID.randomUUID();

    @Test
    void allPassedIsPass() {
        var r = ValidationReportModel.of(taskId, 1, List.of(
            new CheckResult("A", true, ""), new CheckResult("B", true, "")));
        assertThat(r.verdict()).isEqualTo(Verdict.PASS);
        assertThat(r.isPass()).isTrue();
        assertThat(r.id()).isNotNull();
        assertThat(r.attemptNo()).isEqualTo(1);
    }

    @Test
    void anyFailedIsFail() {
        var r = ValidationReportModel.of(taskId, 1, List.of(
            new CheckResult("A", true, ""), new CheckResult("B", false, "bad")));
        assertThat(r.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(r.isPass()).isFalse();
    }

    @Test
    void requiresAtLeastOneCheck() {
        assertThatThrownBy(() -> ValidationReportModel.of(taskId, 1, List.of()))
            .isInstanceOf(DomainException.class)
            .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                .isEqualTo(ResultCode.VALIDATION_ERROR));
    }

    @Test
    void requiresNonNullChecks() {
        assertThatThrownBy(() -> ValidationReportModel.of(taskId, 1, null))
            .isInstanceOf(DomainException.class)
            .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                .isEqualTo(ResultCode.VALIDATION_ERROR));
    }
}
