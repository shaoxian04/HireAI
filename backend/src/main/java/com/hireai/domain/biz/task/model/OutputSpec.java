package com.hireai.domain.biz.task.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.shared.exception.DomainException;

/**
 * The binding output contract a client declares for a task (Hard Invariant #4).
 * Persisted faithfully and immutably; later consumed by automated validation and
 * by dispute arbitration. {@code format} is required; {@code schema} and
 * {@code acceptanceCriteria} are optional free-form fields.
 */
public record OutputSpec(OutputFormat format, String schema, String acceptanceCriteria) {

    public OutputSpec {
        if (format == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Output spec format is required");
        }
    }
}
