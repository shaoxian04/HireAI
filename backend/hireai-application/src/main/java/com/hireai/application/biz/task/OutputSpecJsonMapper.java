package com.hireai.application.biz.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.stereotype.Component;

/**
 * Serialises the {@code OutputSpec} value object to/from its JSON(B) representation. Jackson
 * handles the record natively (canonical constructor), so the domain stays annotation-free.
 *
 * <p>Single shared bean (default name {@code outputSpecJsonMapper}) used by the task and agent
 * repositories (write side), the task read service, and the catalogue controller. It lives in
 * the application layer — the lowest layer all of those depend on — so the repository,
 * infrastructure, and controller modules can share it without anyone depending "upward".
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
