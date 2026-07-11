package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.MatchPreviewAppService;
import com.hireai.application.port.query.MatchPreviewQueryPort;
import com.hireai.application.port.query.MatchPreviewQueryPort.ShortlistCandidateRow;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.PreviewCriteria;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;
import com.hireai.domain.shared.model.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    @Override
    @Transactional(readOnly = true)
    public MatchPreview preview(String category, Money budget) {
        String normalized = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        List<ShortlistCandidateRow> rows = queryPort.findBookableCandidates(normalized);

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
        List<AgentOption> nearMisses = rows.stream()
                .filter(r -> r.price().compareTo(budgetValue) > 0)
                .sorted(Comparator.comparing(ShortlistCandidateRow::price)
                        .thenComparing(ShortlistCandidateRow::agentVersionId))
                .limit(NEAR_MISS_LIMIT)
                .map(this::toOption)
                .toList();

        return new MatchPreview(shortlist, nearMisses);
    }

    private AgentCandidate toCandidate(ShortlistCandidateRow r) {
        return new AgentCandidate(r.agentId(), r.agentVersionId(), r.capabilityCategories(),
                r.price(), r.webhookUrl(), r.maxExecutionSeconds(), r.reputationScore(),
                r.outputSpecJson(), r.maxConcurrent(), r.inFlight(), r.sampleCount());
    }

    private AgentOption toOption(ShortlistCandidateRow r) {
        boolean available = r.inFlight() < r.maxConcurrent();
        return new AgentOption(r.agentId(), r.agentVersionId(), r.agentName(), r.tagline(),
                r.logoUrl(), r.price(), r.reputationScore(), available, r.outputFormat(),
                r.capabilityCategories());
    }
}
