package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.domain.biz.task.enums.OutputFormat;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration mapping of spec D39: an output spec becomes an equivalent Contract (on a Task) or
 * Offer (on an agent version) under the generic Templates.
 */
class OutputSpecContractTest {

    private static final String SCHEMA = "{\"type\":\"object\"}";

    @Test
    void textMapsToTextBasic() {
        OutputSpec spec = new OutputSpec(OutputFormat.TEXT, null, "Three bullet points");

        assertThat(spec.toContract()).isEqualTo(new Contract(new TemplateRef("text_basic", 1), Map.of()));
        assertThat(spec.toOwnOffer()).isEqualTo(Offer.of(new OfferTerm(new TemplateRef("text_basic", 1), Map.of())));
    }

    @Test
    void fileMapsToFileBasic() {
        OutputSpec spec = new OutputSpec(OutputFormat.FILE, "ignored", null);

        assertThat(spec.toContract()).isEqualTo(new Contract(new TemplateRef("file_basic", 1), Map.of()));
        assertThat(spec.toOwnOffer()).isEqualTo(Offer.of(new OfferTerm(new TemplateRef("file_basic", 1), Map.of())));
    }

    @Test
    void jsonMapsToCustomJsonCarryingTheSchema() {
        OutputSpec spec = new OutputSpec(OutputFormat.JSON, SCHEMA, null);

        assertThat(spec.toContract()).isEqualTo(new Contract(new TemplateRef("custom_json", 1),
                Map.of("schema", new ParameterValue.Text(SCHEMA))));
    }

    @Test
    void aJsonOfferAcceptsOnlyTheAgentsOwnSchema() {
        OutputSpec spec = new OutputSpec(OutputFormat.JSON, SCHEMA, null);

        assertThat(spec.toOwnOffer()).isEqualTo(Offer.of(new OfferTerm(new TemplateRef("custom_json", 1),
                Map.of("schema", new ParameterRange.OneOf(Set.of(SCHEMA))))));
    }

    @Test
    void aBlankJsonSchemaLeavesTheParameterUnsetAndTheOfferAcceptsNoSchema() {
        OutputSpec spec = new OutputSpec(OutputFormat.JSON, "   ", null);

        assertThat(spec.toContract()).isEqualTo(new Contract(new TemplateRef("custom_json", 1), Map.of()));
        assertThat(spec.toOwnOffer()).isEqualTo(Offer.of(new OfferTerm(new TemplateRef("custom_json", 1),
                Map.of())));
        // An omitted range accepts only Contracts that leave the schema unset.
    }
}
