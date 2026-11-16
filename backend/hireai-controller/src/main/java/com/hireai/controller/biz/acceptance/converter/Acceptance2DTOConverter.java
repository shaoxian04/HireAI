package com.hireai.controller.biz.acceptance.converter;

import com.hireai.controller.biz.acceptance.dto.ContractDTO;
import com.hireai.controller.biz.acceptance.dto.OfferDTO;
import com.hireai.controller.biz.acceptance.dto.TemplateDTO;
import com.hireai.controller.biz.acceptance.dto.VersionFallbackDTO;
import com.hireai.domain.biz.acceptance.template.info.TemplateLifecycle;
import com.hireai.domain.biz.acceptance.template.info.VersionFallback;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateParameter;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written converters from the acceptance value objects to their outbound DTOs. The JSON
 * shapes match the stored ones (see {@code AcceptanceJsonMapper}), so what a client reads is what
 * was frozen.
 */
public final class Acceptance2DTOConverter {

    private Acceptance2DTOConverter() {
    }

    /** Null for a Task submitted before Contracts existed. */
    public static ContractDTO toDTO(Contract contract) {
        if (contract == null) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        contract.params().forEach((name, value) -> params.put(name, switch (value) {
            case ParameterValue.Number n -> n.value();
            case ParameterValue.Text t -> t.value();
            case ParameterValue.TextSet s -> List.copyOf(s.values());
            case ParameterValue.Flag f -> f.value();
        }));
        return new ContractDTO(contract.template().key(), contract.template().version(), params,
                contract.aiReview() == null ? null : new ContractDTO.AiReviewTermsDTO(
                        contract.aiReview().criteria(), contract.aiReview().fee().value()));
    }

    public static OfferDTO toDTO(Offer offer, BigDecimal price) {
        List<OfferDTO.TermDTO> terms = offer.terms().stream().map(term -> {
            Map<String, Map<String, Object>> params = new LinkedHashMap<>();
            term.params().forEach((name, range) -> params.put(name, switch (range) {
                case ParameterRange.Bound b -> Map.<String, Object>of("bound", b.value());
                case ParameterRange.OneOf o -> Map.<String, Object>of("oneOf", List.copyOf(o.values()));
                case ParameterRange.AnyValue a -> Map.<String, Object>of("any", true);
                case ParameterRange.Flag f -> Map.<String, Object>of("acceptsTrue", f.acceptsTrue());
            }));
            return new OfferDTO.TermDTO(term.template().key(), term.template().version(), params);
        }).toList();
        return new OfferDTO(price, terms, offer.acceptsAiReview(), offer.inspectionCeiling().name());
    }

    /** Null when there was no fallback. */
    public static VersionFallbackDTO toDTO(VersionFallback fallback) {
        if (fallback == null) {
            return null;
        }
        return new VersionFallbackDTO(fallback.current().key(), fallback.current().version(),
                fallback.used().version(), fallback.transitionEndsAt(), fallback.summary());
    }

    public static TemplateDTO toDTO(TemplateModel template) {
        return toDTO(template, null);
    }

    /** {@code lifecycle} (spec D36) is where the version stands now; null leaves those fields out. */
    public static TemplateDTO toDTO(TemplateModel template, TemplateLifecycle lifecycle) {
        return new TemplateDTO(
                template.ref().key(),
                template.ref().version(),
                template.title(),
                template.description(),
                template.outputShape().name(),
                template.checks(),
                template.parameters().stream().map(Acceptance2DTOConverter::toDTO).toList(),
                template.expectationMode().name(),
                template.rulingSettlement(),
                template.status().name(),
                template.publishedAt(),
                lifecycle == null ? null : lifecycle.current(),
                lifecycle == null ? null : lifecycle.takesNewContracts(),
                lifecycle == null ? null : lifecycle.takesNewContractsUntil());
    }

    private static TemplateDTO.ParameterDTO toDTO(TemplateParameter parameter) {
        return new TemplateDTO.ParameterDTO(parameter.name(), parameter.label(), parameter.description(),
                parameter.comparison().wireName(),
                new TemplateDTO.BoundsDTO(parameter.bounds().minimum(), parameter.bounds().maximum(),
                        parameter.bounds().allowedValues(), parameter.bounds().maxLength()));
    }
}
