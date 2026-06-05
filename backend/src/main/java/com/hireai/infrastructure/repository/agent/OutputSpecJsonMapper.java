package com.hireai.infrastructure.repository.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.springframework.stereotype.Component;

/**
 * Serialises the {@code OutputSpec} value object to/from the agent_versions JSONB column.
 * Jackson handles the record natively, so the domain stays annotation-free. Agent-local copy
 * (the Task track owns its own equivalent under infrastructure/repository/task). An explicit
 * bean name is given so this component coexists with the Task track's same-named class under
 * default component scanning (which derives bean names from the short class name).
 */
@Component("agentOutputSpecJsonMapper")
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
