package com.hireai.domain.biz.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.routing.service.impl.RoutingMatchDomainServiceImpl;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingMatchDomainServiceTest {

    private final RoutingMatchDomainService service = new RoutingMatchDomainServiceImpl();

    private TaskRoutingView task(String category, String budget) {
        return new TaskRoutingView(UUID.randomUUID(), category, new BigDecimal(budget), "SUBMITTED",
                "{\"format\":\"JSON\"}");
    }

    private AgentCandidate candidate(UUID versionId, List<String> categories, String price, String reputation) {
        return new AgentCandidate(
                UUID.randomUUID(), versionId, categories,
                new BigDecimal(price), "https://agent.example/hook", 60, new BigDecimal(reputation),
                "{\"format\":\"JSON\"}");
    }

    @Test
    void matchesCandidateAdvertisingTheCategory() {
        UUID wanted = UUID.randomUUID();
        List<AgentCandidate> candidates = List.of(
                candidate(UUID.randomUUID(), List.of("translation"), "10.00", "50.00"),
                candidate(wanted, List.of("summarisation", "translation"), "10.00", "50.00"));

        Optional<UUID> chosen = service.selectAgentVersion(task("summarisation", "30.00"), candidates);

        assertThat(chosen).contains(wanted);
    }

    @Test
    void filtersOutCandidatesPricedAboveBudget() {
        UUID affordable = UUID.randomUUID();
        List<AgentCandidate> candidates = List.of(
                candidate(UUID.randomUUID(), List.of("summarisation"), "40.00", "90.00"),
                candidate(affordable, List.of("summarisation"), "25.00", "50.00"));

        Optional<UUID> chosen = service.selectAgentVersion(task("summarisation", "30.00"), candidates);

        assertThat(chosen).contains(affordable);
    }

    @Test
    void breaksTiesByHighestReputation() {
        UUID best = UUID.randomUUID();
        List<AgentCandidate> candidates = List.of(
                candidate(UUID.randomUUID(), List.of("summarisation"), "10.00", "60.00"),
                candidate(best, List.of("summarisation"), "10.00", "85.00"),
                candidate(UUID.randomUUID(), List.of("summarisation"), "10.00", "72.00"));

        Optional<UUID> chosen = service.selectAgentVersion(task("summarisation", "30.00"), candidates);

        assertThat(chosen).contains(best);
    }

    @Test
    void returnsEmptyWhenNoCandidateMatchesCategory() {
        List<AgentCandidate> candidates = List.of(
                candidate(UUID.randomUUID(), List.of("translation"), "10.00", "90.00"));

        Optional<UUID> chosen = service.selectAgentVersion(task("summarisation", "30.00"), candidates);

        assertThat(chosen).isEmpty();
    }

    @Test
    void returnsEmptyWhenCandidateListIsEmpty() {
        Optional<UUID> chosen = service.selectAgentVersion(task("summarisation", "30.00"), List.of());

        assertThat(chosen).isEmpty();
    }
}
