package com.hireai.domain.biz.acceptance.extraction;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.extraction.enums.ExtractionStatus;
import com.hireai.domain.biz.acceptance.extraction.model.ContractExtraction;
import com.hireai.domain.biz.acceptance.extraction.model.ExtractedRequirement;
import com.hireai.domain.biz.acceptance.extraction.model.ProposedRequirement;
import com.hireai.domain.biz.acceptance.extraction.service.RequirementExtractionDomainService;
import com.hireai.domain.biz.acceptance.extraction.service.impl.RequirementExtractionDomainServiceImpl;
import com.hireai.domain.biz.acceptance.template.enums.ExpectationMode;
import com.hireai.domain.biz.acceptance.template.enums.OutputShape;
import com.hireai.domain.biz.acceptance.template.enums.ParameterComparison;
import com.hireai.domain.biz.acceptance.template.enums.TemplateStatus;
import com.hireai.domain.biz.acceptance.template.model.GenericTemplates;
import com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate;
import com.hireai.domain.biz.acceptance.template.model.ParameterBounds;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateParameter;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement extraction (spec S6, D11, D12): the LLM's proposals are untrusted, so every mapping is
 * checked deterministically against the Template and the Check Library, and a mapping that fails any
 * check is kept as an unmapped requirement instead of shaping the Contract.
 */
class RequirementExtractionDomainServiceTest {

    /** image_generation@1 as seeded by V48 (the IT asserts the seeded copy equals these constants). */
    private static final TemplateModel IMAGE = new TemplateModel(ImageGenerationTemplate.IMAGE_GENERATION_V1,
            "Generated image", "An image", OutputShape.FILE, ImageGenerationTemplate.CHECKS, List.of(
                    new TemplateParameter("formats", "Formats", "", ParameterComparison.SUBSET,
                            ParameterBounds.closedSet(List.of("png", "jpeg", "webp"))),
                    new TemplateParameter("width", "Width", "", ParameterComparison.MAX,
                            ParameterBounds.numeric(BigDecimal.valueOf(16), BigDecimal.valueOf(4096))),
                    new TemplateParameter("height", "Height", "", ParameterComparison.MAX,
                            ParameterBounds.numeric(BigDecimal.valueOf(16), BigDecimal.valueOf(4096))),
                    new TemplateParameter("background_colour", "Background", "", ParameterComparison.ENUM,
                            ParameterBounds.openText(7)),
                    new TemplateParameter("colour_tolerance", "Tolerance", "", ParameterComparison.MIN,
                            ParameterBounds.numeric(BigDecimal.ZERO, BigDecimal.valueOf(255)))),
            ExpectationMode.BUSINESS_VALID,
            Map.of("FULFILLED", BigDecimal.ONE, "PARTIALLY_FULFILLED", new BigDecimal("0.5"),
                    "NOT_FULFILLED", BigDecimal.ZERO),
            TemplateStatus.ACTIVE, Instant.parse("2026-11-01T00:00:00Z"));

    private static final Contract DEFAULTS = new Contract(IMAGE.ref(), Map.of());

    private final RequirementExtractionDomainService service = new RequirementExtractionDomainServiceImpl();
    /** The app passes the full submit check (bounds, version, checkability); bounds are enough here. */
    private final Consumer<Contract> valid = IMAGE::requireValid;

    @Test
    void aRequirementMappedOntoAnAllowedEntryWithInBoundsValuesShapesTheContract() {
        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, List.of(
                mapped("A 512 by 512 pixel image", "IMAGE_DIMENSIONS", Map.of("width", 512, "height", 512)),
                mapped("On a white background", "IMAGE_BACKGROUND_COLOUR", Map.of("background_colour", "#ffffff")),
                unmapped("It should feel friendly")), valid);

        assertThat(extraction.status()).isEqualTo(ExtractionStatus.EXTRACTED);
        assertThat(extraction.contract().params()).containsOnly(
                Map.entry("width", new ParameterValue.Number(BigDecimal.valueOf(512))),
                Map.entry("height", new ParameterValue.Number(BigDecimal.valueOf(512))),
                Map.entry("background_colour", new ParameterValue.Text("#ffffff")));
        assertThat(extraction.requirements()).extracting(ExtractedRequirement::text, ExtractedRequirement::check)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A 512 by 512 pixel image", "IMAGE_DIMENSIONS"),
                        org.assertj.core.groups.Tuple.tuple("On a white background", "IMAGE_BACKGROUND_COLOUR"),
                        org.assertj.core.groups.Tuple.tuple("It should feel friendly", null));
        assertThat(extraction.requirements().get(2).mapped()).isFalse();
    }

    @Test
    void anEntryTheTemplateDoesNotListIsDowngradedToUnmapped() {
        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, List.of(
                mapped("At most 2000 characters", "TEXT_MAX_LENGTH", Map.of("max_length", 2000)),
                mapped("Invented check", "IMAGE_IS_PRETTY", Map.of())), valid);

        assertThat(extraction.contract()).isEqualTo(DEFAULTS);
        assertThat(extraction.requirements()).allSatisfy(r -> {
            assertThat(r.mapped()).isFalse();
            assertThat(r.unmappedReason()).contains("no check");
        });
    }

    @Test
    void anOutOfBoundsOrWrongKindValueIsDowngradedToUnmapped() {
        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, List.of(
                mapped("A 10000 pixel wide banner", "IMAGE_DIMENSIONS", Map.of("width", 10000)),
                mapped("Width of 'big'", "IMAGE_DIMENSIONS", Map.of("width", "big")),
                mapped("As a GIF", "IMAGE_FORMAT", Map.of("formats", List.of("gif")))), valid);

        assertThat(extraction.contract()).isEqualTo(DEFAULTS);
        assertThat(extraction.requirements()).noneMatch(ExtractedRequirement::mapped);
    }

    @Test
    void aParameterTheEntryDoesNotReadIsDowngraded() {
        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, List.of(
                mapped("512 wide", "IMAGE_DIMENSIONS", Map.of("width", 512, "background_colour", "#000000"))), valid);

        assertThat(extraction.contract()).isEqualTo(DEFAULTS);
        assertThat(extraction.requirements().get(0).unmappedReason()).contains("background_colour");
    }

    @Test
    void nullValuesStandForUnsetParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("width", 512);
        params.put("background_colour", null); // strict structured output sends every parameter
        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, List.of(
                new ProposedRequirement("512 wide", "IMAGE_DIMENSIONS", params)), valid);

        assertThat(extraction.contract().params()).containsOnlyKeys("width");
        assertThat(extraction.requirements().get(0).mapped()).isTrue();
    }

    @Test
    void aMappingThatSetsNothingTheCheckRunsOnIsDowngraded() {
        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, List.of(
                mapped("Colours close enough", "IMAGE_BACKGROUND_COLOUR", Map.of("colour_tolerance", 10)),
                mapped("Any image format is fine", "IMAGE_FORMAT", Map.of())), valid);

        assertThat(extraction.contract()).isEqualTo(DEFAULTS);
        assertThat(extraction.requirements().get(0).mapped()).isFalse();
        // IMAGE_FORMAT always runs, so a parameterless mapping onto it stands.
        assertThat(extraction.requirements().get(1).mapped()).isTrue();
    }

    @Test
    void aFindingIsNeverARequirement() {
        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, List.of(
                mapped("Don't repeat an earlier brief", "DUPLICATE_BRIEF", Map.of())), valid);

        assertThat(extraction.requirements().get(0).mapped()).isFalse();
    }

    @Test
    void aLaterMappingThatConflictsWithAnEarlierOneIsDowngraded() {
        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, List.of(
                mapped("512 wide", "IMAGE_DIMENSIONS", Map.of("width", 512)),
                mapped("Actually 640 wide", "IMAGE_DIMENSIONS", Map.of("width", 640))), valid);

        assertThat(extraction.contract().params()).containsOnly(
                Map.entry("width", new ParameterValue.Number(BigDecimal.valueOf(512))));
        assertThat(extraction.requirements().get(1).unmappedReason()).contains("conflicts");
    }

    @Test
    void theBaseContractsValuesAreKeptAndNeverOverridden() {
        Contract base = new Contract(IMAGE.ref(), Map.of("width", new ParameterValue.Number(BigDecimal.valueOf(256))));

        ContractExtraction extraction = service.adopt(IMAGE, base, List.of(
                mapped("1024 wide", "IMAGE_DIMENSIONS", Map.of("width", 1024)),
                mapped("256 tall", "IMAGE_DIMENSIONS", Map.of("height", 256))), valid);

        assertThat(extraction.contract().params()).containsOnly(
                Map.entry("width", new ParameterValue.Number(BigDecimal.valueOf(256))),
                Map.entry("height", new ParameterValue.Number(BigDecimal.valueOf(256))));
    }

    @Test
    void theAppsFullCheckAlsoDowngrades() {
        Consumer<Contract> refusesColour = c -> {
            IMAGE.requireValid(c);
            if (c.params().containsKey("background_colour")) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, "background_colour must be #rrggbb");
            }
        };

        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, List.of(
                mapped("A red background", "IMAGE_BACKGROUND_COLOUR", Map.of("background_colour", "red"))),
                refusesColour);

        assertThat(extraction.contract()).isEqualTo(DEFAULTS);
        assertThat(extraction.requirements().get(0).unmappedReason()).contains("#rrggbb");
    }

    @Test
    void untrustedOutputIsCappedInCountAndLength() {
        ProposedRequirement[] many = new ProposedRequirement[40];
        Arrays.fill(many, unmapped("x".repeat(5000)));

        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, Arrays.asList(many), valid);

        assertThat(extraction.requirements()).hasSize(RequirementExtractionDomainService.MAX_REQUIREMENTS);
        assertThat(extraction.requirements().get(0).text())
                .hasSizeLessThanOrEqualTo(RequirementExtractionDomainService.MAX_TEXT_LENGTH);
    }

    @Test
    void blankRequirementsAreDropped() {
        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, List.of(unmapped("  "), unmapped(null)), valid);

        assertThat(extraction.requirements()).isEmpty();
    }

    @Test
    void withoutTheLlmTheContractIsTheDefaults() {
        ContractExtraction extraction = ContractExtraction.unavailable(DEFAULTS);

        assertThat(extraction.status()).isEqualTo(ExtractionStatus.UNAVAILABLE);
        assertThat(extraction.contract()).isEqualTo(DEFAULTS);
        assertThat(extraction.requirements()).isEmpty();
    }

    // ---- units (D11/D12: only unambiguous requirements map) ----

    /** text_basic@1: min_length / max_length count characters. */
    private static final TemplateModel TEXT = new TemplateModel(GenericTemplates.TEXT_BASIC_V1,
            "Plain text", "Text", OutputShape.TEXT,
            List.of("FORMAT_TEXT_NON_EMPTY", "TEXT_MIN_LENGTH", "TEXT_MAX_LENGTH"), List.of(
                    new TemplateParameter("min_length", "Fewest characters", "", ParameterComparison.MAX,
                            ParameterBounds.numeric(BigDecimal.ONE, BigDecimal.valueOf(100_000))),
                    new TemplateParameter("max_length", "Most characters", "", ParameterComparison.MIN,
                            ParameterBounds.numeric(BigDecimal.ONE, BigDecimal.valueOf(100_000)))),
            ExpectationMode.FAITHFUL_TO_SOURCE,
            Map.of("FULFILLED", BigDecimal.ONE, "PARTIALLY_FULFILLED", new BigDecimal("0.5"),
                    "NOT_FULFILLED", BigDecimal.ZERO),
            TemplateStatus.ACTIVE, Instant.parse("2026-11-01T00:00:00Z"));

    @Test
    void aWordCountMappedOntoACharacterCountIsDowngraded() {
        ContractExtraction extraction = service.adopt(TEXT, new Contract(TEXT.ref(), Map.of()), List.of(
                mapped("Between 20 to 200 words", "TEXT_MAX_LENGTH", Map.of("max_length", 200)),
                mapped("At least 3 paragraphs", "TEXT_MIN_LENGTH", Map.of("min_length", 3))), TEXT::requireValid);

        assertThat(extraction.contract().params()).isEmpty();
        assertThat(extraction.requirements()).noneMatch(ExtractedRequirement::mapped);
        assertThat(extraction.requirements().get(0).unmappedReason()).contains("words").contains("characters");
        assertThat(extraction.requirements().get(1).unmappedReason()).contains("paragraphs");
    }

    @Test
    void aCharacterCountMappedOntoACharacterCountStands() {
        ContractExtraction extraction = service.adopt(TEXT, new Contract(TEXT.ref(), Map.of()), List.of(
                mapped("Keep it under 800 characters", "TEXT_MAX_LENGTH", Map.of("max_length", 800))),
                TEXT::requireValid);

        assertThat(extraction.contract().params()).containsOnly(
                Map.entry("max_length", new ParameterValue.Number(BigDecimal.valueOf(800))));
        assertThat(extraction.requirements().get(0).mapped()).isTrue();
    }

    @Test
    void aPixelSizeIsNotReadAsCharactersAndAPageCountIsNotReadAsPixels() {
        ContractExtraction extraction = service.adopt(IMAGE, DEFAULTS, List.of(
                mapped("A 512 by 512 pixel image", "IMAGE_DIMENSIONS", Map.of("width", 512, "height", 512)),
                mapped("Two pages wide", "IMAGE_DIMENSIONS", Map.of("width", 2048))), valid);

        assertThat(extraction.requirements().get(0).mapped()).isTrue();
        assertThat(extraction.requirements().get(1).mapped()).isFalse();
        assertThat(extraction.requirements().get(1).unmappedReason()).contains("pages").contains("pixels");
    }

    // ---- a client naming the checks it relies on (mode 2) ----

    @Test
    void namingAnEntryTheTemplateListsAndSwitchesOnIsAccepted() {
        Contract contract = new Contract(IMAGE.ref(), Map.of("width", new ParameterValue.Number(BigDecimal.valueOf(64))));

        assertThatCode(() -> service.requireNamedChecks(IMAGE, contract, List.of("IMAGE_DIMENSIONS", "IMAGE_FORMAT")))
                .doesNotThrowAnyException();
    }

    @Test
    void namingAnUnknownCheckIsRefused() {
        assertThatThrownBy(() -> service.requireNamedChecks(IMAGE, DEFAULTS, List.of("IMAGE_IS_PRETTY")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("IMAGE_IS_PRETTY")
                .hasMessageContaining("IMAGE_DIMENSIONS");
    }

    @Test
    void namingACheckWhoseParametersAreUnsetIsRefused() {
        assertThatThrownBy(() -> service.requireNamedChecks(IMAGE, DEFAULTS, List.of("IMAGE_BACKGROUND_COLOUR")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("background_colour");
    }

    private static ProposedRequirement mapped(String text, String check, Map<String, Object> params) {
        return new ProposedRequirement(text, check, new HashMap<>(params));
    }

    private static ProposedRequirement unmapped(String text) {
        return new ProposedRequirement(text, null, Map.of());
    }
}
