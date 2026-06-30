package com.hireai.domain.biz.adjudication.service;

import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.service.impl.ValidationDomainServiceImpl;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskResultModel;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ValidationDomainServiceImplTest {

    // Fake SchemaValidator: parses with a trivial brace check, honours a "SCHEMA_OK"/"SCHEMA_BAD" marker.
    private final SchemaValidator fake = (payload, schema) -> {
        boolean valid = payload != null && payload.trim().startsWith("{") && payload.trim().endsWith("}");
        if (!valid) return new JsonCheckResult(false, false, false, "bad json");
        if (schema == null || schema.isBlank()) return new JsonCheckResult(true, false, false, "no schema");
        if ("SCHEMA_BAD".equals(schema)) return new JsonCheckResult(true, true, false, "mismatch");
        return new JsonCheckResult(true, true, true, "ok");
    };
    private final ValidationDomainService svc = new ValidationDomainServiceImpl(fake);
    private final UUID taskId = UUID.randomUUID();

    private TaskResultModel result(String payload, String url) {
        return TaskResultModel.record(taskId, "COMPLETED", payload, url);
    }

    @Test
    void textNonEmptyPasses() {
        var spec = new OutputSpec(OutputFormat.TEXT, null, null);
        assertThat(svc.validate(spec, result("a summary", null), 1).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void textBlankFails() {
        var spec = new OutputSpec(OutputFormat.TEXT, null, null);
        assertThat(svc.validate(spec, result("   ", null), 1).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void jsonValidNoSchemaPasses() {
        var spec = new OutputSpec(OutputFormat.JSON, null, null);
        assertThat(svc.validate(spec, result("{\"a\":1}", null), 1).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void jsonInvalidFails() {
        var spec = new OutputSpec(OutputFormat.JSON, null, null);
        assertThat(svc.validate(spec, result("not json", null), 1).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void jsonSchemaMismatchFails() {
        var spec = new OutputSpec(OutputFormat.JSON, "SCHEMA_BAD", null);
        assertThat(svc.validate(spec, result("{\"a\":1}", null), 1).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void fileHttpsUrlPasses() {
        var spec = new OutputSpec(OutputFormat.FILE, null, null);
        assertThat(svc.validate(spec, result("{}", "https://cdn.example.com/x.pdf"), 1).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void fileNonHttpsFails() {
        var spec = new OutputSpec(OutputFormat.FILE, null, null);
        assertThat(svc.validate(spec, result("{}", "http://cdn.example.com/x.pdf"), 1).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void fileMissingUrlFails() {
        var spec = new OutputSpec(OutputFormat.FILE, null, null);
        assertThat(svc.validate(spec, result("{}", null), 1).verdict()).isEqualTo(Verdict.FAIL);
    }
}
