package com.hireai.domain.biz.adjudication.service.impl;

import com.hireai.domain.biz.acceptance.check.model.AcceptanceEvidence;
import com.hireai.domain.biz.acceptance.check.model.ExtractedInvoice;
import com.hireai.domain.biz.acceptance.check.model.Finding;
import com.hireai.domain.biz.acceptance.check.model.InvoiceReading;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.domain.biz.acceptance.check.service.CheckLibrary;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.model.GenericTemplates;
import com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate;
import com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate;
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
import java.util.Set;

/**
 * Runs the checks of the generic Templates (D39) on a result:
 * <ul>
 *   <li>{@code text_basic}: non-blank text, then {@code min_length}/{@code max_length} when set,
 *       counted in characters (Unicode code points) of the payload as delivered;</li>
 *   <li>{@code custom_json}: the payload parses, then matches the {@code schema} when set;</li>
 *   <li>{@code file_basic}: the result link is HTTPS, then, on its DeliverySnapshot (D24, never the
 *       live link), {@code FILE_FETCHED} (fetched, not empty, within the platform limit), then
 *       {@code FILE_MEDIA_TYPE} / {@code FILE_MAX_BYTES} when the Contract sets {@code media_types} /
 *       {@code max_bytes}. The media type is the one judged from the bytes ({@code DeliveredMediaType}).</li>
 *   <li>{@code image_generation}: the same link and fetch checks, then its Check Library entries
 *       ({@link CheckLibrary}) on the frozen copy's pixels, and its Findings;</li>
 *   <li>{@code ap_invoice_extraction}: the result is one JSON object ({@code INVOICE_JSON}), its fields
 *       are there and well formed, then the invoice's own arithmetic, judged by the Contract's
 *       expectation mode; and its Findings (duplicates, figures that don't add up).</li>
 * </ul>
 * A self-reported anomaly turns a rejection into review on every Template (D7).
 */
public class ValidationDomainServiceImpl implements ValidationDomainService {

    /** Errors name the request field, so a form can show them under it ({@code lib/formErrors}). */
    private static final String SCHEMA_FIELD = "outputSpec.schema: ";

    static final String FILE_FETCHED = "FILE_FETCHED";
    static final String FILE_MEDIA_TYPE = "FILE_MEDIA_TYPE";
    static final String FILE_MAX_BYTES = "FILE_MAX_BYTES";
    /** A review entry for an anomaly the Agent reported that no rejecting check explains. */
    static final String SELF_REPORTED_ANOMALY = CheckResult.SELF_REPORTED_ANOMALY;

    private final SchemaValidator schemaValidator;

    public ValidationDomainServiceImpl(SchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    @Override
    public ValidationReportModel judge(Contract contract, TaskResultModel result, AcceptanceEvidence evidence,
                                       int attemptNo) {
        TemplateRef template = contract.template();
        DeliveryOutcome delivery = evidence.delivery();
        List<CheckResult> checks = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        if (template.equals(GenericTemplates.TEXT_BASIC_V1)) {
            checks.addAll(checkText(result.resultPayloadJson(), contract));
        } else if (template.equals(GenericTemplates.CUSTOM_JSON_V1)) {
            checks.addAll(checkJson(result.resultPayloadJson(), text(contract, GenericTemplates.SCHEMA)));
        } else if (template.equals(GenericTemplates.FILE_BASIC_V1)) {
            CheckResult link = checkFile(result.resultUrl());
            checks.add(link);
            if (link.passed()) {
                checks.addAll(checkDelivery(contract, requireDelivery(delivery)));
            }
        } else if (template.equals(ImageGenerationTemplate.IMAGE_GENERATION_V1)) {
            CheckResult link = checkFile(result.resultUrl());
            checks.add(link);
            DeliverySnapshot snapshot = null;
            if (link.passed()) {
                List<CheckResult> fetched = checkDelivery(contract, requireDelivery(delivery));
                checks.addAll(fetched);
                if (fetched.stream().allMatch(CheckResult::passed)
                        && delivery instanceof DeliveryOutcome.Captured captured) {
                    snapshot = captured.snapshot();
                }
            }
            CheckLibrary.Subject subject = new CheckLibrary.Subject(contract, snapshot, evidence.image());
            for (String entry : ImageGenerationTemplate.CHECKS) {
                if (CheckLibrary.isCheck(entry) && snapshot != null) {
                    CheckLibrary.check(entry, subject).ifPresent(checks::add);
                } else if (CheckLibrary.isFinding(entry)) {
                    CheckLibrary.find(entry, subject, evidence.facts()).ifPresent(findings::add);
                }
            }
        } else if (template.equals(InvoiceExtractionTemplate.AP_INVOICE_EXTRACTION_V1)) {
            InvoiceReading reading = evidence.invoice();
            if (reading == null) {
                throw new IllegalStateException("An invoice result is judged on its reading; read it first (needsInvoice)");
            }
            ExtractedInvoice invoice = reading instanceof InvoiceReading.Read read ? read.invoice() : null;
            checks.add(invoice != null
                    ? new CheckResult(InvoiceExtractionTemplate.INVOICE_JSON, true, "a JSON object")
                    : new CheckResult(InvoiceExtractionTemplate.INVOICE_JSON, false,
                    "the result must be one JSON object with the invoice's fields: "
                            + ((InvoiceReading.Unreadable) reading).reason()));
            CheckLibrary.Subject subject = CheckLibrary.Subject.ofInvoice(contract, invoice, evidence.anomalies(),
                    result.taskId());
            boolean shapeOk = invoice != null;
            for (String entry : InvoiceExtractionTemplate.CHECKS) {
                if (CheckLibrary.isCheck(entry) && shapeOk) {
                    Optional<CheckResult> c = CheckLibrary.check(entry, subject);
                    c.ifPresent(checks::add);
                    if (entry.equals(InvoiceExtractionTemplate.INVOICE_FIELDS) && c.isPresent() && c.get().rejects()) {
                        shapeOk = false; // arithmetic on a malformed invoice would only repeat the problem
                    }
                } else if (CheckLibrary.isFinding(entry)) {
                    CheckLibrary.find(entry, subject, evidence.facts()).ifPresent(findings::add);
                }
            }
        } else {
            // A Contract naming another Template fails its Template check at submit, so reaching
            // here means a Template shipped without its checks.
            throw new IllegalStateException("No checks implemented for Template " + template);
        }
        Set<String> eligible = template.equals(ImageGenerationTemplate.IMAGE_GENERATION_V1)
                ? ImageGenerationTemplate.ANOMALY_ELIGIBLE
                : template.equals(InvoiceExtractionTemplate.AP_INVOICE_EXTRACTION_V1)
                ? InvoiceExtractionTemplate.ANOMALY_ELIGIBLE : Set.of();
        return ValidationReportModel.of(result.taskId(), attemptNo,
                applyAnomalies(checks, evidence.anomalies(), eligible), findings, evidence.anomalies());
    }

    /**
     * D7: an anomaly the Agent reported sends the result to review instead of a deterministic
     * rejection, but only for the Template's anomaly-eligible (content) checks: one naming such a
     * check that rejected turns it into review. An anomaly naming another check of the result
     * changes nothing (a wrong format, link, fetch or size is always reworked); one naming no check
     * of the result adds a review entry of its own, so it reaches the Client.
     */
    private static List<CheckResult> applyAnomalies(List<CheckResult> checks, List<SelfReportedAnomaly> anomalies,
                                                    Set<String> anomalyEligible) {
        if (anomalies.isEmpty()) {
            return checks;
        }
        List<CheckResult> out = new ArrayList<>(checks);
        for (SelfReportedAnomaly anomaly : anomalies) {
            boolean explained = false;
            for (int i = 0; i < out.size(); i++) {
                CheckResult c = out.get(i);
                if (c.rule().equals(anomaly.check())) {
                    explained = true;
                    if (c.rejects() && anomalyEligible.contains(c.rule())) {
                        out.set(i, CheckResult.review(c.rule(), c.detail() + ". The Agent reported: "
                                + anomaly.description()));
                    }
                    // Any other named check is left as it is: an ineligible rejection stays a
                    // rejection (reworked), and the anomaly is still kept on the report.
                }
            }
            if (!explained) {
                out.add(CheckResult.review(SELF_REPORTED_ANOMALY, (anomaly.check() == null ? ""
                        : "about " + anomaly.check() + ": ") + anomaly.description()));
            }
        }
        return out;
    }

    private static DeliveryOutcome requireDelivery(DeliveryOutcome delivery) {
        if (delivery == null) {
            throw new IllegalStateException("A file result with an HTTPS link is judged on its "
                    + "DeliverySnapshot; fetch it first (needsDelivery)");
        }
        return delivery;
    }

    @Override
    public boolean needsPixels(Contract contract) {
        return contract.template().equals(ImageGenerationTemplate.IMAGE_GENERATION_V1);
    }

    @Override
    public boolean needsFindingFacts(Contract contract) {
        return contract.template().equals(ImageGenerationTemplate.IMAGE_GENERATION_V1)
                || contract.template().equals(InvoiceExtractionTemplate.AP_INVOICE_EXTRACTION_V1);
    }

    @Override
    public boolean needsInvoice(Contract contract) {
        return contract.template().equals(InvoiceExtractionTemplate.AP_INVOICE_EXTRACTION_V1);
    }

    @Override
    public ValidationReportModel validate(OutputSpec legacySpec, TaskResultModel result, int attemptNo) {
        return validate(legacySpec.toContract(), result, attemptNo);
    }

    @Override
    public boolean needsDelivery(Contract contract, TaskResultModel result) {
        TemplateRef template = contract.template();
        return (template.equals(GenericTemplates.FILE_BASIC_V1)
                || template.equals(ImageGenerationTemplate.IMAGE_GENERATION_V1))
                && checkFile(result.resultUrl()).passed();
    }

    @Override
    public long deliveryByteLimit(Contract contract, long platformLimit) {
        BigDecimal max = number(contract, GenericTemplates.MAX_BYTES);
        if (max == null) {
            return platformLimit;
        }
        // Never longValueExact: a fractional limit (refused at submit) must not throw in the gate.
        return max.compareTo(BigDecimal.valueOf(platformLimit)) >= 0 ? platformLimit
                : max.setScale(0, java.math.RoundingMode.FLOOR).longValue();
    }

    private List<CheckResult> checkDelivery(Contract contract, DeliveryOutcome delivery) {
        BigDecimal maxBytes = number(contract, GenericTemplates.MAX_BYTES);
        List<CheckResult> out = new ArrayList<>();
        switch (delivery) {
            case DeliveryOutcome.Unavailable u ->
                    out.add(new CheckResult(FILE_FETCHED, false, "could not fetch the file: " + u.reason()));
            case DeliveryOutcome.TooLarge t -> {
                if (maxBytes != null && maxBytes.compareTo(BigDecimal.valueOf(t.limitBytes())) <= 0) {
                    out.add(new CheckResult(FILE_MAX_BYTES, false,
                            "larger than " + maxBytes.toPlainString() + " bytes, the most allowed"));
                } else {
                    out.add(new CheckResult(FILE_FETCHED, false, "larger than " + t.limitBytes()
                            + " bytes, the most the platform keeps; reading stopped there"));
                }
            }
            case DeliveryOutcome.Captured c -> {
                DeliverySnapshot snapshot = c.snapshot();
                boolean nonEmpty = snapshot.sizeBytes() > 0;
                out.add(new CheckResult(FILE_FETCHED, nonEmpty, nonEmpty
                        ? "fetched and frozen: " + snapshot.sizeBytes() + " bytes, sha256 " + snapshot.sha256()
                        : "the file is empty"));
                Set<String> accepted = textSet(contract, GenericTemplates.MEDIA_TYPES);
                if (accepted != null) {
                    String type = snapshot.mediaType();
                    String declared = snapshot.declaredMediaType() != null && !snapshot.declaredMediaType().equals(type)
                            ? " (the Agent declared " + snapshot.declaredMediaType() + ")" : "";
                    out.add(new CheckResult(FILE_MEDIA_TYPE, accepted.contains(type),
                            "the file is " + type + declared + "; accepted: " + String.join(", ", accepted)));
                }
                if (maxBytes != null) {
                    out.add(new CheckResult(FILE_MAX_BYTES,
                            BigDecimal.valueOf(snapshot.sizeBytes()).compareTo(maxBytes) <= 0,
                            snapshot.sizeBytes() + " bytes; at most " + maxBytes.toPlainString() + " allowed"));
                }
            }
        }
        return out;
    }

    @Override
    public void requireCheckable(Contract contract) {
        String schema = text(contract, GenericTemplates.SCHEMA);
        if (schema != null) {
            requireUsableSchema(schema);
        }
        for (var param : contract.params().entrySet()) {
            if ((GenericTemplates.WHOLE_NUMBER_PARAMETERS.contains(param.getKey())
                    || ImageGenerationTemplate.WHOLE_NUMBER_PARAMETERS.contains(param.getKey()))
                    && param.getValue() instanceof ParameterValue.Number n
                    && n.value().stripTrailingZeros().scale() > 0) {
                throw invalid(param.getKey() + " must be a whole number; got " + n.value().toPlainString());
            }
        }
        String colour = text(contract, ImageGenerationTemplate.BACKGROUND_COLOUR);
        if (contract.template().equals(ImageGenerationTemplate.IMAGE_GENERATION_V1) && colour != null
                && !CheckLibrary.isHexColour(colour)) {
            throw invalid(ImageGenerationTemplate.BACKGROUND_COLOUR + " must be a colour written as #rrggbb, "
                    + "such as #ffffff; got " + colour);
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
            throw invalid(SCHEMA_FIELD + "is not a valid JSON Schema: " + problem.get()
                    + ". Give a JSON Schema object, such as {\"type\":\"object\"}, or leave it blank to accept any JSON.");
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

    private static Set<String> textSet(Contract contract, String name) {
        return contract.params().get(name) instanceof ParameterValue.TextSet t ? t.values() : null;
    }

    private static BigDecimal number(Contract contract, String name) {
        return contract.params().get(name) instanceof ParameterValue.Number n ? n.value() : null;
    }

    private static DomainException invalid(String message) {
        return new DomainException(ResultCode.VALIDATION_ERROR, message);
    }
}
