package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.model.GenericTemplates;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.utility.exception.DomainException;

import java.util.Map;
import java.util.Set;

/**
 * The output contract a client declares for a task, persisted faithfully and immutably and still
 * consumed by automated validation and by dispute arbitration. {@code format} is required;
 * {@code schema} and {@code acceptanceCriteria} are optional free-form fields.
 *
 * <p>Being replaced by the Contract (ADR 0009): {@link #toContract()} and {@link #toOwnOffer()}
 * are the D39 migration mapping onto the generic Templates, mirrored for existing rows by the
 * V39 SQL backfill. {@code acceptanceCriteria} is prose, so it maps onto no parameter.
 */
public record OutputSpec(OutputFormat format, String schema, String acceptanceCriteria) {

    public OutputSpec {
        if (format == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Output spec format is required");
        }
    }

    /**
     * The output spec a Task submitted with only a Contract records (spec S6 mode 2, until #110 retires
     * output_spec): the Template's shape, and the Contract's schema when it sets one.
     */
    public static OutputSpec forContract(com.hireai.domain.biz.acceptance.template.enums.OutputShape shape,
                                         Contract contract, String acceptanceCriteria) {
        String schema = contract.params().get(GenericTemplates.SCHEMA) instanceof ParameterValue.Text t ? t.value() : null;
        return new OutputSpec(OutputFormat.valueOf(shape.name()), schema, acceptanceCriteria);
    }

    /** The Contract a Task submitted with this spec freezes: TEXT, FILE and JSON map one to one. */
    public Contract toContract() {
        if (format == OutputFormat.JSON && hasSchema()) {
            return new Contract(GenericTemplates.CUSTOM_JSON_V1,
                    Map.of(GenericTemplates.SCHEMA, new ParameterValue.Text(schema)));
        }
        return new Contract(template(), Map.of());
    }

    /**
     * The equivalent Offer of an agent version declaring this spec. A JSON Offer accepts only the
     * Agent's own schema (or, without one, only Contracts that set no schema).
     */
    public Offer toOwnOffer() {
        if (format == OutputFormat.JSON && hasSchema()) {
            return Offer.of(new OfferTerm(GenericTemplates.CUSTOM_JSON_V1,
                    Map.of(GenericTemplates.SCHEMA, new ParameterRange.OneOf(Set.of(schema)))));
        }
        return Offer.of(new OfferTerm(template(), Map.of()));
    }

    private TemplateRef template() {
        return switch (format) {
            case TEXT -> GenericTemplates.TEXT_BASIC_V1;
            case JSON -> GenericTemplates.CUSTOM_JSON_V1;
            case FILE -> GenericTemplates.FILE_BASIC_V1;
        };
    }

    private boolean hasSchema() {
        return schema != null && !schema.isBlank();
    }
}
