package com.hireai.domain.biz.task.routing.service;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.coverage.info.ContractTerms;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.enums.ExpectationMode;
import com.hireai.domain.biz.acceptance.template.enums.OutputShape;
import com.hireai.domain.biz.acceptance.template.enums.ParameterComparison;
import com.hireai.domain.biz.acceptance.template.enums.TemplateStatus;
import com.hireai.domain.biz.acceptance.template.model.ParameterBounds;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateParameter;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.MatchCriteria;
import com.hireai.domain.biz.task.info.PreviewCriteria;
import com.hireai.domain.biz.task.routing.info.NoMatchExplanation;
import com.hireai.domain.biz.task.routing.info.ScoredCandidate;
import com.hireai.domain.biz.task.routing.service.impl.RoutingMatchDomainServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offer coverage as one more eligibility filter in the single ranking engine (spec D34), and the
 * no-match explanation with relax suggestions (D35).
 */
class RoutingMatchCoverageTest {

    private static final TemplateRef REF = new TemplateRef("demo_file", 1);
    private static final TemplateModel TEMPLATE = new TemplateModel(REF, "Demo file", "A file", OutputShape.FILE,
            List.of(), List.of(
            new TemplateParameter("max_bytes", "Max size", "Largest file", ParameterComparison.MIN,
                    ParameterBounds.numeric(BigDecimal.ONE, new BigDecimal("1000"))),
            new TemplateParameter("media_types", "Types", "Allowed types", ParameterComparison.SUBSET,
                    ParameterBounds.closedSet(List.of("image/png", "application/pdf")))),
            ExpectationMode.NONE,
            Map.of("FULFILLED", BigDecimal.ONE, "PARTIALLY_FULFILLED", new BigDecimal("0.5"),
                    "NOT_FULFILLED", BigDecimal.ZERO),
            TemplateStatus.ACTIVE, Instant.parse("2026-11-15T00:00:00Z"));

    private final RoutingMatchDomainService matcher = new RoutingMatchDomainServiceImpl(
            MatchingPolicy.defaults().withEpsilon(0.0), new Random(1));

    private static MatchCriteria criteria(Map<String, ParameterValue> params) {
        return new PreviewCriteria("design", new BigDecimal("30.00"))
                .withTerms(new ContractTerms(TEMPLATE, new Contract(REF, params)));
    }

    private static final Map<String, ParameterValue> PNG_UNDER_500 = Map.of(
            "max_bytes", new ParameterValue.Number(new BigDecimal("500")),
            "media_types", new ParameterValue.TextSet(Set.of("image/png")));

    private static AgentCandidate agent(UUID versionId, String price, Double similarity, Offer offer) {
        return new AgentCandidate(UUID.randomUUID(), versionId, List.of("design"), new BigDecimal(price),
                "https://agent.example/hook", 60, new BigDecimal("50.00"), "{\"format\":\"FILE\"}",
                5, 0, 0, similarity, offer);
    }

    /** An Offer on the demo Template accepting files from {@code minBytes} and these media types. */
    private static Offer offer(String minBytes, Set<String> mediaTypes) {
        return Offer.of(new OfferTerm(REF, Map.of(
                "max_bytes", new ParameterRange.Bound(new BigDecimal(minBytes)),
                "media_types", new ParameterRange.OneOf(mediaTypes))));
    }

    private static Offer otherTemplate() {
        return Offer.of(new OfferTerm(new TemplateRef("text_basic", 1), Map.of()));
    }

    @Test
    void onlyAgentsWhoseOfferCoversTheContractRank() {
        UUID covered = UUID.randomUUID();
        List<ScoredCandidate> ranked = matcher.rank(criteria(PNG_UNDER_500), List.of(
                agent(covered, "10.00", null, offer("100", Set.of("image/png"))),
                agent(UUID.randomUUID(), "10.00", null, offer("600", Set.of("image/png"))),
                agent(UUID.randomUUID(), "10.00", null, offer("100", Set.of("application/pdf"))),
                agent(UUID.randomUUID(), "10.00", null, otherTemplate())));

        assertThat(ranked).extracting(sc -> sc.candidate().agentVersionId()).containsExactly(covered);
    }

    @Test
    void selectionAndTheCoverageCheckUseTheSameFilter() {
        UUID covered = UUID.randomUUID();
        AgentCandidate uncovered = agent(UUID.randomUUID(), "5.00", null, otherTemplate());
        AgentCandidate good = agent(covered, "10.00", null, offer("100", Set.of("image/png")));

        assertThat(matcher.selectOne(criteria(PNG_UNDER_500), List.of(uncovered, good))).contains(covered);
        assertThat(matcher.covers(criteria(PNG_UNDER_500), good)).isTrue();
        assertThat(matcher.covers(criteria(PNG_UNDER_500), uncovered)).isFalse();
    }

    @Test
    void criteriaWithoutContractTermsFilterNoOffer() {
        MatchCriteria noTerms = new PreviewCriteria("design", new BigDecimal("30.00"));

        assertThat(matcher.rank(noTerms, List.of(agent(UUID.randomUUID(), "10.00", null, otherTemplate()))))
                .hasSize(1);
    }

    @Test
    void anAgentWithoutAnOfferCoversNoContract() {
        assertThat(matcher.rank(criteria(Map.of()), List.of(agent(UUID.randomUUID(), "10.00", null, null))))
                .isEmpty();
    }

    @Test
    void theExplanationNamesEachBlockingParameterWithItsAgentCount() {
        NoMatchExplanation explanation = matcher.explainNoMatch(criteria(PNG_UNDER_500), List.of(
                agent(UUID.randomUUID(), "10.00", null, offer("600", Set.of("image/png"))),
                agent(UUID.randomUUID(), "10.00", null, offer("700", Set.of("image/png"))),
                agent(UUID.randomUUID(), "10.00", null, offer("100", Set.of("application/pdf"))),
                agent(UUID.randomUUID(), "10.00", null, offer("900", Set.of("application/pdf"))),
                agent(UUID.randomUUID(), "10.00", null, otherTemplate()))).orElseThrow();

        assertThat(explanation.exclusions()).containsExactly(
                new NoMatchExplanation.Exclusion(NoMatchExplanation.Blocker.PARAMETER, "max_bytes", 3),
                new NoMatchExplanation.Exclusion(NoMatchExplanation.Blocker.PARAMETER, "media_types", 2),
                new NoMatchExplanation.Exclusion(NoMatchExplanation.Blocker.TEMPLATE, null, 1));
        // Dropping one parameter only helps the Agents it alone excluded.
        assertThat(explanation.suggestions()).containsExactly(
                new NoMatchExplanation.RelaxSuggestion("max_bytes", 2),
                new NoMatchExplanation.RelaxSuggestion("media_types", 1));
    }

    @Test
    void theRelevanceFloorIsNamedAsABlockerToo() {
        NoMatchExplanation explanation = matcher.explainNoMatch(criteria(PNG_UNDER_500), List.of(
                agent(UUID.randomUUID(), "10.00", 0.05, offer("100", Set.of("image/png"))),
                agent(UUID.randomUUID(), "10.00", 0.05, offer("600", Set.of("image/png"))))).orElseThrow();

        assertThat(explanation.exclusions()).containsExactly(
                new NoMatchExplanation.Exclusion(NoMatchExplanation.Blocker.RELEVANCE_FLOOR, null, 2),
                new NoMatchExplanation.Exclusion(NoMatchExplanation.Blocker.PARAMETER, "max_bytes", 1));
        // Dropping max_bytes would still leave that Agent below the floor: no suggestion.
        assertThat(explanation.suggestions()).isEmpty();
        assertThat(matcher.noCloseMatch(criteria(PNG_UNDER_500), List.of(
                agent(UUID.randomUUID(), "10.00", 0.05, offer("100", Set.of("image/png")))))).isTrue();
        assertThat(matcher.noCloseMatch(criteria(PNG_UNDER_500), List.of(
                agent(UUID.randomUUID(), "10.00", 0.05, offer("600", Set.of("image/png"))))))
                .as("an uncovered Agent below the floor is not a near match")
                .isFalse();
    }

    @Test
    void agentsAboveBudgetOrOutsideTheCategoryAreNotPartOfTheExplanation() {
        NoMatchExplanation explanation = matcher.explainNoMatch(criteria(PNG_UNDER_500), List.of(
                agent(UUID.randomUUID(), "99.00", null, offer("600", Set.of("image/png"))))).orElseThrow();

        assertThat(explanation.exclusions()).isEmpty();
        assertThat(explanation.summary()).isEqualTo("No Agent serves this category within the budget.");
    }

    @Test
    void thereIsNoExplanationWhenSomethingMatches() {
        assertThat(matcher.explainNoMatch(criteria(PNG_UNDER_500), List.of(
                agent(UUID.randomUUID(), "10.00", null, offer("100", Set.of("image/png")))))).isEmpty();
    }

    @Test
    void theSummaryNamesTheBlockersInPlainWords() {
        NoMatchExplanation explanation = matcher.explainNoMatch(criteria(PNG_UNDER_500), List.of(
                agent(UUID.randomUUID(), "10.00", null, offer("600", Set.of("image/png"))),
                agent(UUID.randomUUID(), "10.00", 0.05, offer("100", Set.of("image/png"))),
                agent(UUID.randomUUID(), "10.00", null, otherTemplate()))).orElseThrow();

        assertThat(explanation.summary()).isEqualTo("No eligible Agent: parameter max_bytes excluded 1 Agent; "
                + "the relevance floor excluded 1 Agent; 1 Agent does not offer Template demo_file@1.");
    }
}
