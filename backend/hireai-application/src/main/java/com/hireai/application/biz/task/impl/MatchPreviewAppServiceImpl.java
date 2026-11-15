package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.application.biz.offering.embedding.TaskTextEmbeddingAppService;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.task.info.MatchCriteria;
import com.hireai.application.biz.task.MatchPreviewAppService;
import com.hireai.application.port.query.MatchPreviewQueryPort;
import com.hireai.application.port.query.MatchPreviewQueryPort.ShortlistCandidateRow;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.category.repository.CategoryRepository;
import com.hireai.domain.biz.offering.embedding.info.TextVector;
import com.hireai.domain.biz.task.info.PreviewCriteria;
import com.hireai.domain.biz.task.model.ClientDeadline;
import com.hireai.domain.biz.task.model.ExecutionGrace;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;
import com.hireai.domain.shared.model.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MatchPreviewAppServiceImpl implements MatchPreviewAppService {

    private static final int SHORTLIST_LIMIT = 5;
    private static final int NEAR_MISS_LIMIT = 3;

    private final MatchPreviewQueryPort queryPort;
    private final RoutingMatchDomainService matcher;
    private final CategoryRepository categoryRepository;
    private final TaskTextEmbeddingAppService taskTextEmbeddingAppService;
    /** Validates the preview's Contract and resolves its Template version (#91). */
    private final TemplateReadAppService templateReadAppService;
    /** The app's Clock bean: the preview's deadline is counted from the request. */
    private final Clock clock;
    /** Added to an Agent's max execution time, as routing stamps it (ExecutionConfig). */
    private final ExecutionGrace executionGrace;

    /**
     * Not transactional: the task text's embedding (cache or provider, up to its timeout) is resolved
     * first and must not hold a connection; the rest is single read queries.
     */
    @Override
    public MatchPreview preview(String title, String description, String category, Money budget,
                                Set<UUID> excludedAgentIds, Long deadlineSeconds, Contract contract) {
        // An unknown slug is a 400 naming the valid ones (#80).
        String normalized = categoryRepository.load().requireKnown(category);
        MatchCriteria criteria = new PreviewCriteria(normalized, budget.value());
        if (contract != null) {
            // A Contract outside its Template's bounds (or an unknown Template) is a 400, as at submit.
            templateReadAppService.requireValid(contract);
            criteria = criteria.withTerms(templateReadAppService.termsFor(contract));
        }
        Set<UUID> excluded = excludedAgentIds == null ? Set.of() : excludedAgentIds;
        // Excluded before ranking, so they leave the shortlist and the near-misses alike.
        Long longestExecution = null;
        if (deadlineSeconds != null) {
            Instant now = clock.instant();
            longestExecution = ClientDeadline.after(now, deadlineSeconds)
                    .longestExecutionSeconds(now, executionGrace);
        }
        TextVector taskVector = taskTextEmbeddingAppService.vectorFor(title, description).orElse(null);
        List<ShortlistCandidateRow> rows = queryPort.findBookableCandidates(normalized, longestExecution, taskVector)
                .stream()
                .filter(r -> !excluded.contains(r.agentId()))
                .toList();

        Map<UUID, ShortlistCandidateRow> byVersion = rows.stream()
                .collect(Collectors.toMap(ShortlistCandidateRow::agentVersionId, Function.identity(),
                        (a, b) -> a));

        List<AgentCandidate> candidates = rows.stream().map(this::toCandidate).toList();
        List<AgentOption> shortlist = matcher
                .rank(criteria, candidates).stream()
                .limit(SHORTLIST_LIMIT)
                .map(sc -> toOption(byVersion.get(sc.candidate().agentVersionId())))
                .toList();

        BigDecimal budgetValue = budget.value();
        // Near-misses meet the relevance floor and Offer coverage too: an off-topic Agent, or one that
        // would not accept the Contract, is no useful suggestion.
        MatchCriteria terms = criteria;
        List<AgentOption> nearMisses = rows.stream()
                .filter(r -> r.price().compareTo(budgetValue) > 0)
                .filter(r -> matcher.passesRelevanceFloor(toCandidate(r)))
                .filter(r -> matcher.covers(terms, toCandidate(r)))
                .sorted(Comparator.comparing(ShortlistCandidateRow::price)
                        .thenComparing(ShortlistCandidateRow::agentVersionId))
                .limit(NEAR_MISS_LIMIT)
                .map(this::toOption)
                .toList();

        return new MatchPreview(shortlist, nearMisses, matcher.noCloseMatch(criteria, candidates),
                matcher.explainNoMatch(criteria, candidates).orElse(null));
    }

    private AgentCandidate toCandidate(ShortlistCandidateRow r) {
        return new AgentCandidate(r.agentId(), r.agentVersionId(), r.capabilityCategories(),
                r.price(), r.webhookUrl(), r.maxExecutionSeconds(), r.reputationScore(),
                r.outputSpecJson(), r.maxConcurrent(), r.inFlight(), r.sampleCount(), r.similarity(), r.offer());
    }

    private AgentOption toOption(ShortlistCandidateRow r) {
        boolean available = r.inFlight() < r.maxConcurrent();
        return new AgentOption(r.agentId(), r.agentVersionId(), r.agentName(), r.tagline(),
                r.logoUrl(), r.price(), r.reputationScore(), available, r.outputFormat(),
                r.capabilityCategories(), r.reliabilitySum(), r.reliabilityCount());
    }
}
