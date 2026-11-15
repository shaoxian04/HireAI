package com.hireai.domain.biz.adjudication.service.impl;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.model.GenericTemplates;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import com.hireai.domain.biz.adjudication.service.SchemaValidator;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs the checks of the generic Templates (D39) on a result:
 * <ul>
 *   <li>{@code text_basic}: non-blank text, then {@code min_length}/{@code max_length} when set,
 *       counted in characters (Unicode code points) of the payload as delivered;</li>
 *   <li>{@code custom_json}: the payload parses, then matches the {@code schema} when set;</li>
 *   <li>{@code file_basic}: the result link is HTTPS ({@code FILE_MEDIA_TYPE}/{@code FILE_MAX_BYTES}
 *       wait for the DeliverySnapshot, #97).</li>
 * </ul>
 */
public class ValidationDomainServiceImpl implements ValidationDomainService {

    /** Errors name the request field, so a form can show them under it ({@code lib/formErrors}). */
    private static final String SCHEMA_FIELD = "outputSpec.schema: ";

    private final SchemaValidator schemaValidator;

    public ValidationDomainServiceImpl(SchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    @Override
    public ValidationReportModel validate(Contract contract, TaskResultModel result, int attemptNo) {
        TemplateRef template = contract.template();
        List<CheckResult> checks = new ArrayList<>();
        if (template.equals(GenericTemplates.TEXT_BASIC_V1)) {
            checks.addAll(checkText(result.resultPayloadJson(), contract));
        } else if (template.equals(GenericTemplates.CUSTOM_JSON_V1)) {
            checks.addAll(checkJson(result.resultPayloadJson(), text(contract, GenericTemplates.SCHEMA)));
        } else if (template.equals(GenericTemplates.FILE_BASIC_V1)) {
            checks.add(checkFile(result.resultUrl()));
        } else {
            // Only the generic Templates exist; a Contract naming another one fails its Template
            // check at submit, so reaching here means a Template shipped without its checks.
            throw new IllegalStateException("No checks implemented for Template " + template);
        }
        return ValidationReportModel.of(result.taskId(), attemptNo, checks);
    }

    @Override
    public ValidationReportModel validate(OutputSpec legacySpec, TaskResultModel result, int attemptNo) {
        return validate(legacySpec.toContract(), result, attemptNo);
    }

    @Override
    public void requireCheckable(Contract contract) {
        String schema = text(contract, GenericTemplates.SCHEMA);
        if (schema != null) {
            requireUsableSchema(schema);
        }
        BigDecimal min = number(contract, GenericTemplates.MIN_LENGTH);
        BigDecimal max = number(contract, GenericTemplates.MAX_LENGTH);
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw invalid(GenericTemplates.MIN_LENGTH + " (" + min.toPlainString() + ") is greater than "
                    + GenericTemplates.MAX_LENGTH + " (" + max.toPlainString() + "), so no text could pass");
        }
    }

    @Override
    public void requireCheckable(Offer offer) {
        for (OfferTerm term : offer.terms()) {
            if (term.params().get(GenericTemplates.SCHEMA) instanceof ParameterRange.OneOf oneOf) {
                oneOf.values().forEach(this::requireUsableSchema);
            }
        }
    }

    private void requireUsableSchema(String schema) {
        Optional<String> problem = schemaValidator.problemWith(schema);
        if (problem.isPresent()) {
            throw invalid(SCHEMA_FIELD + "is not a valid JSON Schema (" + problem.get()
                    + "). Give a JSON Schema object, or leave it blank to accept any JSON");
        }
    }

    private List<CheckResult> checkText(String payload, Contract contract) {
        List<CheckResult> out = new ArrayList<>();
        boolean ok = payload != null && !payload.isBlank();
        out.add(new CheckResult("FORMAT_TEXT_NON_EMPTY", ok, ok ? "non-empty text" : "empty/blank payload"));
        int length = payload == null ? 0 : payload.codePointCount(0, payload.length());
        BigDecimal actual = BigDecimal.valueOf(length);
        BigDecimal min = number(contract, GenericTemplates.MIN_LENGTH);
        if (min != null) {
            out.add(new CheckResult("TEXT_MIN_LENGTH", actual.compareTo(min) >= 0,
                    length + " characters; at least " + min.toPlainString() + " required"));
        }
        BigDecimal max = number(contract, GenericTemplates.MAX_LENGTH);
        if (max != null) {
            out.add(new CheckResult("TEXT_MAX_LENGTH", actual.compareTo(max) <= 0,
                    length + " characters; at most " + max.toPlainString() + " allowed"));
        }
        return out;
    }

    private List<CheckResult> checkJson(String payload, String schema) {
        JsonCheckResult r = schemaValidator.check(payload, schema);
        List<CheckResult> out = new ArrayList<>();
        out.add(new CheckResult("FORMAT_JSON_PARSEABLE", r.validJson(), r.detail()));
        if (r.validJson()) {
            if (r.schemaApplicable()) {
                out.add(new CheckResult("SCHEMA_MATCH", r.schemaMatches(), r.detail()));
            } else {
                // No schema in the Contract, or (only on a Task from before Contracts) a prose one.
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

    private static String text(Contract contract, String name) {
        return contract.params().get(name) instanceof ParameterValue.Text t && !t.value().isBlank()
                ? t.value() : null;
    }

    private static BigDecimal number(Contract contract, String name) {
        return contract.params().get(name) instanceof ParameterValue.Number n ? n.value() : null;
    }

    private static DomainException invalid(String message) {
        return new DomainException(ResultCode.VALIDATION_ERROR, message);
    }
}
