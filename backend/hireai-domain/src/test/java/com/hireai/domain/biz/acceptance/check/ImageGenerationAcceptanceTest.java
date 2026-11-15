package com.hireai.domain.biz.acceptance.check;

import com.hireai.domain.biz.acceptance.check.model.AcceptanceEvidence;
import com.hireai.domain.biz.acceptance.check.model.DecodedImage;
import com.hireai.domain.biz.acceptance.check.model.FindingFacts;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import com.hireai.domain.biz.acceptance.template.model.ImageGenerationTemplate;
import com.hireai.domain.biz.adjudication.enums.CheckOutcome;
import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import com.hireai.domain.biz.adjudication.service.SchemaValidator;
import com.hireai.domain.biz.adjudication.service.impl.ValidationDomainServiceImpl;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The acceptance engine on {@code image_generation@1}: format, dimensions, background colour, Findings, anomalies. */
class ImageGenerationAcceptanceTest {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFFF0000;

    private final ValidationDomainServiceImpl engine = new ValidationDomainServiceImpl(new SchemaValidator() {
        @Override
        public JsonCheckResult check(String payload, String schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> problemWith(String schema) {
            return Optional.empty();
        }
    });
    private final UUID taskId = UUID.randomUUID();
    private final TaskResultModel result = TaskResultModel.record(taskId, "COMPLETED", "{}", "https://cdn.example/a.png");

    @Test
    void anImageMeetingEveryTermPasses() {
        ValidationReportModel report = judge(contract(512, 512, "#FFFFFF"), "image/png", image(512, 512, WHITE));

        assertThat(report.verdict()).isEqualTo(Verdict.PASS);
        assertThat(rules(report)).containsExactly("FORMAT_FILE_HTTPS_URL:PASS", "FILE_FETCHED:PASS",
                "IMAGE_FORMAT:PASS", "IMAGE_DIMENSIONS:PASS", "IMAGE_BACKGROUND_COLOUR:PASS");
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void theWrongBackgroundRejectsAndSaysWhatWasMeasured() {
        ValidationReportModel report = judge(contract(512, 512, "#ffffff"), "image/png", image(512, 512, RED));

        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(report.rejectingChecks()).singleElement().satisfies(c -> {
            assertThat(c.rule()).isEqualTo("IMAGE_BACKGROUND_COLOUR");
            assertThat(c.detail()).contains("#ff0000").contains("#ffffff");
        });
    }

    @Test
    void aColourWithinTheToleranceStillPasses() {
        ValidationReportModel near = judge(contract(null, null, "#ffffff"), "image/png", image(4, 4, 0xFFF0F0F0));
        Map<String, ParameterValue> strict = new HashMap<>(contract(null, null, "#ffffff").params());
        strict.put(ImageGenerationTemplate.COLOUR_TOLERANCE, new ParameterValue.Number(BigDecimal.valueOf(5)));
        ValidationReportModel tight = judge(new Contract(ImageGenerationTemplate.IMAGE_GENERATION_V1, strict),
                "image/png", image(4, 4, 0xFFF0F0F0));

        assertThat(near.verdict()).isEqualTo(Verdict.PASS);
        assertThat(tight.verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void aTransparentBackgroundIsNotTheColourAsked() {
        ValidationReportModel report = judge(contract(null, null, "#ffffff"), "image/png", image(4, 4, 0x00FFFFFF));

        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(report.rejectingChecks().get(0).detail()).contains("transparent");
    }

    @Test
    void theWrongSizeOrFormatRejects() {
        assertThat(judge(contract(512, 256, null), "image/png", image(512, 512, WHITE)).rejectingChecks())
                .extracting(CheckResult::rule).containsExactly("IMAGE_DIMENSIONS");
        assertThat(judge(contract(null, null, null), "application/pdf", null).rejectingChecks())
                .extracting(CheckResult::rule).containsExactly("IMAGE_FORMAT");
    }

    @Test
    void pixelsThePlatformCannotReadSendTheResultToReviewRatherThanGuess() {
        ValidationReportModel report = judge(contract(512, 512, "#ffffff"), "image/webp", null);

        assertThat(report.verdict()).isEqualTo(Verdict.REVIEW);
        assertThat(rules(report)).contains("IMAGE_FORMAT:PASS", "IMAGE_DIMENSIONS:REVIEW",
                "IMAGE_BACKGROUND_COLOUR:REVIEW");
        assertThat(report.isPass()).isFalse();
        assertThat(report.isRejected()).isFalse();
    }

    @Test
    void aSelfReportedAnomalyTurnsTheRejectionItExplainsIntoReview() {
        SelfReportedAnomaly anomaly = new SelfReportedAnomaly("IMAGE_BACKGROUND_COLOUR",
                "The brief asks for a white logo on a white background; I used red so it stays visible.");
        ValidationReportModel report = judge(contract(512, 512, "#ffffff"), "image/png", image(512, 512, RED),
                List.of(anomaly), FindingFacts.NONE);

        assertThat(report.verdict()).isEqualTo(Verdict.REVIEW);
        assertThat(rules(report)).contains("IMAGE_BACKGROUND_COLOUR:REVIEW").doesNotContain("SELF_REPORTED_ANOMALY:REVIEW");
        assertThat(report.anomalies()).containsExactly(anomaly);
    }

    @Test
    void anAnomalyNoCheckExplainsStillSendsTheResultToReview() {
        ValidationReportModel report = judge(contract(512, 512, "#ffffff"), "image/png", image(512, 512, WHITE),
                List.of(new SelfReportedAnomaly(null, "The brief names two brand colours.")), FindingFacts.NONE);

        assertThat(report.verdict()).isEqualTo(Verdict.REVIEW);
        assertThat(rules(report)).endsWith("SELF_REPORTED_ANOMALY:REVIEW");
    }

    @Test
    void aDuplicateBriefIsAFindingThatNeverChangesTheVerdict() {
        UUID earlier = UUID.randomUUID();
        ValidationReportModel passing = judge(contract(512, 512, "#ffffff"), "image/png", image(512, 512, WHITE),
                List.of(), new FindingFacts(List.of(earlier)));
        ValidationReportModel failing = judge(contract(512, 512, "#ffffff"), "image/png", image(512, 512, RED),
                List.of(), new FindingFacts(List.of(earlier)));

        assertThat(passing.verdict()).isEqualTo(Verdict.PASS);
        assertThat(failing.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(passing.findings()).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("DUPLICATE_BRIEF");
            assertThat(f.detail()).contains(earlier.toString());
        });
        assertThat(rules(passing)).noneMatch(r -> r.startsWith("DUPLICATE_BRIEF"));
    }

    @Test
    void aBackgroundColourThatIsNotHexCannotBeChecked() {
        assertThatThrownBy(() -> engine.requireCheckable(contract(null, null, "white")))
                .isInstanceOf(DomainException.class).hasMessageContaining("#rrggbb");
    }

    @Test
    void anImageResultIsFetchedAndDecodedBeforeItIsJudged() {
        Contract c = contract(null, null, null);
        assertThat(engine.needsDelivery(c, result)).isTrue();
        assertThat(engine.needsPixels(c)).isTrue();
    }

    @Test
    void aReportStoredBeforeOutcomesReadsBackFromPassed() {
        assertThat(new CheckResult("X", false, "d", null).outcome()).isEqualTo(CheckOutcome.REJECT);
        assertThat(new CheckResult("X", true, "d", null).outcome()).isEqualTo(CheckOutcome.PASS);
    }

    // ---- helpers ----

    private ValidationReportModel judge(Contract contract, String mediaType, DecodedImage image) {
        return judge(contract, mediaType, image, List.of(), FindingFacts.NONE);
    }

    private ValidationReportModel judge(Contract contract, String mediaType, DecodedImage image,
                                        List<SelfReportedAnomaly> anomalies, FindingFacts facts) {
        DeliverySnapshot snapshot = new DeliverySnapshot(UUID.randomUUID(), taskId, result.id(), result.resultUrl(),
                mediaType, mediaType, 2048, "a".repeat(64), Instant.now());
        return engine.judge(contract, result,
                new AcceptanceEvidence(new DeliveryOutcome.Captured(snapshot), image, anomalies, facts), 1);
    }

    private static Contract contract(Integer width, Integer height, String background) {
        Map<String, ParameterValue> params = new HashMap<>();
        if (width != null) params.put(ImageGenerationTemplate.WIDTH, new ParameterValue.Number(BigDecimal.valueOf(width)));
        if (height != null) params.put(ImageGenerationTemplate.HEIGHT, new ParameterValue.Number(BigDecimal.valueOf(height)));
        if (background != null) params.put(ImageGenerationTemplate.BACKGROUND_COLOUR, new ParameterValue.Text(background));
        params.put(ImageGenerationTemplate.FORMATS, new ParameterValue.TextSet(Set.of("image/png", "image/webp")));
        return new Contract(ImageGenerationTemplate.IMAGE_GENERATION_V1, params);
    }

    private static DecodedImage image(int width, int height, int borderArgb) {
        int[] border = new int[2 * (width + height)];
        Arrays.fill(border, borderArgb);
        return new DecodedImage(width, height, border);
    }

    private static List<String> rules(ValidationReportModel report) {
        return report.checks().stream().map(c -> c.rule() + ":" + c.outcome()).toList();
    }
}
