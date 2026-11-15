package com.hireai.application.biz.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Every parameter kind survives storage, in the documented V39 JSON shapes. */
class AcceptanceJsonMapperTest {

    private final AcceptanceJsonMapper mapper = new AcceptanceJsonMapper(new ObjectMapper());
    private final TemplateRef ref = new TemplateRef("demo_work", 2);

    @Test
    void aContractOfEveryKindRoundTripsAsNaturalJson() {
        Contract contract = new Contract(ref, Map.of(
                "max_pages", new ParameterValue.Number(new BigDecimal("12")),
                "languages", new ParameterValue.TextSet(Set.of("fr", "en")),
                "tone", new ParameterValue.Text("formal"),
                "cite_sources", new ParameterValue.Flag(true)));

        String json = mapper.toJson(contract);

        assertThat(json).isEqualTo("{\"template\":\"demo_work\",\"version\":2,\"params\":{\"cite_sources\":true,"
                + "\"languages\":[\"en\",\"fr\"],\"max_pages\":12,\"tone\":\"formal\"}}");
        assertThat(mapper.contractFromJson(json)).isEqualTo(contract);
    }

    @Test
    void anOfferOfEveryRangeKindRoundTripsAsTaggedObjects() {
        Offer offer = Offer.of(new OfferTerm(ref, Map.of(
                "max_pages", new ParameterRange.Bound(new BigDecimal("40")),
                "languages", new ParameterRange.OneOf(Set.of("en")),
                "tone", new ParameterRange.AnyValue(),
                "cite_sources", new ParameterRange.Flag(false))));

        String json = mapper.toJson(offer);

        assertThat(json).isEqualTo("{\"terms\":[{\"template\":\"demo_work\",\"version\":2,\"params\":{"
                + "\"cite_sources\":{\"acceptsTrue\":false},\"languages\":{\"oneOf\":[\"en\"]},"
                + "\"max_pages\":{\"bound\":40},\"tone\":{\"any\":true}}}]}");
        assertThat(mapper.offerFromJson(json)).isEqualTo(offer);
    }
}
