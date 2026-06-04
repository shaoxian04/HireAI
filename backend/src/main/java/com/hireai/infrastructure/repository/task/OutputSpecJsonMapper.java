package com.hireai.infrastructure.repository.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.springframework.stereotype.Component;

/**
 * Serialises the {@code OutputSpec} value object to/from the JSONB column. Jackson
 * handles the record natively (canonical constructor), so the domain stays annotation-free.
 */
@Component
public class OutputSpecJsonMapper {

    private final ObjectMapper objectMapper;

    public OutputSpecJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(OutputSpec outputSpec) {
        try {
            return objectMapper.writeValueAsString(outputSpec);
        } catch (Exception exception) {
            throw new DomainException(ResultCode.INTERNAL_ERROR, "Failed to serialise output spec");
        }
    }

    public OutputSpec fromJson(String json) {
        try {
            return objectMapper.readValue(json, OutputSpec.class);
        } catch (Exception exception) {
            throw new DomainException(ResultCode.INTERNAL_ERROR, "Failed to read output spec");
        }
    }
}
