package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.offering.embedding.TaskTextEmbeddingAppService;
import com.hireai.application.biz.task.MatchPreviewAppService;
import com.hireai.application.port.query.MatchPreviewQueryPort;
import com.hireai.application.port.query.MatchPreviewQueryPort.ShortlistCandidateRow;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.category.repository.CategoryRepository;
import com.hireai.domain.biz.offering.embedding.info.TextVector;
import com.hireai.domain.biz.task.info.PreviewCriteria;
import com.hireai.domain.biz.task.model.ClientDeadline;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;
import com.hireai.domain.shared.model.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
    /** The app's Clock bean: the preview's deadline is counted from the request. */
    private final Clock clock;

    /**
     * Grace added to an Agent's max execution time, as routing stamps it (same property). NON-final
     * so {@code @RequiredArgsConstructor} skips it; the inline default applies in plain-{@code new}
     * unit tests.
     */
    @Value("${hireai.execution.grace-seconds:60}")
    private long executionGraceSeconds = 60;

    /**
     * Not transactional: the task text's embedding (cache or provider, up to its timeout) is resolved
     * first and must not hold a connection; the rest is single read queries.
     */
    @Override
    public MatchPreview preview(String title, String description, String category, Money budget,
                                Set<UUID> excludedAgentIds, Long deadlineSeconds) {
        // An unknown slug is a 400 naming the valid ones (#80).
        String normalized = categoryRepository.load().requireKnown(category);
        Set<UUID> excluded = excludedAgentIds == null ? Set.of() : excludedAgentIds;
        // Excluded before ranking, so they leave the shortlist and the near-misses alike.
        Long longestExecution = null;
        if (deadlineSeconds != null) {
            Instant now = clock.instant();
            longestExecution = ClientDeadline.after(now, deadlineSeconds)
                    .longestExecutionSeconds(now, executionGraceSeconds);
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
                .rank(new PreviewCriteria(normalized, budget.value()), candidates).stream()
                .limit(SHORTLIST_LIMIT)
                .map(sc -> toOption(byVersion.get(sc.candidate().agentVersionId())))
                .toList();

        BigDecimal budgetValue = budget.value();
        // Near-misses meet the relevance floor too: an off-topic Agent is no useful suggestion.
        List<AgentOption> nearMisses = rows.stream()
                .filter(r -> r.price().compareTo(budgetValue) > 0)
                .filter(r -> matcher.passesRelevanceFloor(toCandidate(r)))
                .sorted(Comparator.comparing(ShortlistCandidateRow::price)
                        .thenComparing(ShortlistCandidateRow::agentVersionId))
                .limit(NEAR_MISS_LIMIT)
                .map(this::toOption)
                .toList();

        boolean noCloseMatch = shortlist.isEmpty() && rows.stream()
                .anyMatch(r -> r.price().compareTo(budgetValue) <= 0 && !matcher.passesRelevanceFloor(toCandidate(r)));
        return new MatchPreview(shortlist, nearMisses, noCloseMatch);
    }

    private AgentCandidate toCandidate(ShortlistCandidateRow r) {
        return new AgentCandidate(r.agentId(), r.agentVersionId(), r.capabilityCategories(),
                r.price(), r.webhookUrl(), r.maxExecutionSeconds(), r.reputationScore(),
                r.outputSpecJson(), r.maxConcurrent(), r.inFlight(), r.sampleCount(), r.similarity());
    }

    private AgentOption toOption(ShortlistCandidateRow r) {
        boolean available = r.inFlight() < r.maxConcurrent();
        return new AgentOption(r.agentId(), r.agentVersionId(), r.agentName(), r.tagline(),
                r.logoUrl(), r.price(), r.reputationScore(), available, r.outputFormat(),
                r.capabilityCategories(), r.reliabilitySum(), r.reliabilityCount());
    }
}
