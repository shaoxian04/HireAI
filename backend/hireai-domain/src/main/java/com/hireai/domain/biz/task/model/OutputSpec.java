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
 * An output spec: {@code format} (required), and the optional {@code schema} and prose
 * {@code acceptanceCriteria}. Since #110 it is never a contract: the Task's frozen Contract is the
 * single binding contract (Hard Invariant #4). It survives in two roles only:
 * <ul>
 *   <li>an <b>input shorthand</b> on submit and Agent registration, mapped onto a Contract or Offer
 *       by {@link #toContract()} / {@link #toOwnOffer()} (the D39 mapping onto the generic Templates);</li>
 *   <li>the <b>wire description</b> of a Task's Contract ({@link #describing}) in WorkOrders,
 *       rejection notices, arbitration requests and the task API, so existing Agents keep reading
 *       {@code outputSpec.format} / {@code schema}.</li>
 * </ul>
 * {@code acceptanceCriteria} is prose that maps onto no Template parameter: it is stored on the Task
 * beside the Contract as intent evidence for the Client and the arbitrator, and read by requirement
 * extraction on a mode-1 submit, but never checked automatically.
 */
public record OutputSpec(OutputFormat format, String schema, String acceptanceCriteria) {

    public OutputSpec {
        if (format == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Output spec format is required");
        }
    }

    /**
     * The wire description of {@code contract}: {@code format} (its Template's shape), the Contract's
     * schema when it sets one, and the Client's prose {@code acceptanceCriteria}.
     */
    public static OutputSpec describing(OutputFormat format, Contract contract, String acceptanceCriteria) {
        String schema = contract.params().get(GenericTemplates.SCHEMA) instanceof ParameterValue.Text t ? t.value() : null;
        return new OutputSpec(format, schema, acceptanceCriteria);
    }

    /** {@link #describing(OutputFormat, Contract, String)} from a Template's shape. */
    public static OutputSpec describing(com.hireai.domain.biz.acceptance.template.enums.OutputShape shape,
                                        Contract contract, String acceptanceCriteria) {
        return describing(OutputFormat.valueOf(shape.name()), contract, acceptanceCriteria);
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
