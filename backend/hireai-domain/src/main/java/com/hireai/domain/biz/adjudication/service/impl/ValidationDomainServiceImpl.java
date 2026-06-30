package com.hireai.domain.biz.adjudication.service.impl;

import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import com.hireai.domain.biz.adjudication.service.SchemaValidator;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskResultModel;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class ValidationDomainServiceImpl implements ValidationDomainService {

    private final SchemaValidator schemaValidator;

    public ValidationDomainServiceImpl(SchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    @Override
    public ValidationReportModel validate(OutputSpec spec, TaskResultModel result, int attemptNo) {
        List<CheckResult> checks = new ArrayList<>();
        switch (spec.format()) {
            case TEXT -> checks.add(checkText(result.resultPayloadJson()));
            case JSON -> checks.addAll(checkJson(result.resultPayloadJson(), spec.schema()));
            case FILE -> checks.add(checkFile(result.resultUrl()));
        }
        return ValidationReportModel.of(result.taskId(), attemptNo, checks);
    }

    private CheckResult checkText(String payload) {
        boolean ok = payload != null && !payload.isBlank();
        return new CheckResult("FORMAT_TEXT_NON_EMPTY", ok, ok ? "non-empty text" : "empty/blank payload");
    }

    private List<CheckResult> checkJson(String payload, String schema) {
        JsonCheckResult r = schemaValidator.check(payload, schema);
        List<CheckResult> out = new ArrayList<>();
        out.add(new CheckResult("FORMAT_JSON_PARSEABLE", r.validJson(), r.detail()));
        if (r.validJson()) {
            if (r.schemaApplicable()) {
                out.add(new CheckResult("SCHEMA_MATCH", r.schemaMatches(), r.detail()));
            } else {
                out.add(new CheckResult("SCHEMA_SKIPPED", true, r.detail()));
            }
        }
        return out;
    }

    private CheckResult checkFile(String url) {
        boolean ok;
        String detail;
        if (url == null || url.isBlank()) {
            ok = false; detail = "no resultUrl";
        } else {
            try {
                URI uri = URI.create(url);
                ok = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
                detail = ok ? "https url" : "url is not https";
            } catch (IllegalArgumentException e) {
                ok = false; detail = "malformed url";
            }
        }
        return new CheckResult("FORMAT_FILE_HTTPS_URL", ok, detail);
    }
}
