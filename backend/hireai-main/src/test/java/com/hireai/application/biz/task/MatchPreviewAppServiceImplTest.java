package com.hireai.application.biz.task;

import com.hireai.application.biz.task.MatchPreviewAppService.AgentOption;
import com.hireai.application.biz.task.MatchPreviewAppService.MatchPreview;
import com.hireai.application.biz.task.impl.MatchPreviewAppServiceImpl;
import com.hireai.application.port.query.MatchPreviewQueryPort;
import com.hireai.application.port.query.MatchPreviewQueryPort.ShortlistCandidateRow;
import com.hireai.domain.biz.task.routing.service.MatchingPolicy;
import com.hireai.domain.biz.task.routing.service.impl.RoutingMatchDomainServiceImpl;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchPreviewAppServiceImplTest {

    private final MatchPreviewQueryPort queryPort = mock(MatchPreviewQueryPort.class);

    private MatchPreviewAppService service() {
        var matcher = new RoutingMatchDomainServiceImpl(
                new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 0.0), new Random(1)); // epsilon 0 -> deterministic
        return new MatchPreviewAppServiceImpl(queryPort, matcher);
    }

    private ShortlistCandidateRow row(String name, String price, String rep, int maxConc, long inFlight, long samples) {
        return new ShortlistCandidateRow(UUID.randomUUID(), UUID.randomUUID(), name, "tag", "logo",
                new BigDecimal(price), new BigDecimal(rep), List.of("summarisation"),
                "{\"format\":\"JSON\"}", "JSON", "https://a/hook", 60, maxConc, inFlight, samples);
    }

    @Test
    void shortlistHoldsOnlyInBudgetRankedBest_nearMissHoldsOverBudget() {
        when(queryPort.findBookableCandidates(anyString())).thenReturn(List.of(
                row("Cheap+Good", "10.00", "90.00", 5, 0, 10),
                row("Midrange", "15.00", "50.00", 5, 0, 10),
                row("Pricey", "30.00", "70.00", 5, 0, 10)));      // over budget 20 -> near-miss

        MatchPreview preview = service().preview("summarisation", Money.of("20.00"));

        assertThat(preview.shortlist()).extracting(AgentOption::agentName)
                .containsExactly("Cheap+Good", "Midrange");        // both in budget, best-first
        assertThat(preview.nearMisses()).extracting(AgentOption::agentName).containsExactly("Pricey");
    }

    @Test
    void nearMissIsPriceAscendingAndCappedAtThree() {
        when(queryPort.findBookableCandidates(anyString())).thenReturn(List.of(
                row("In", "10.00", "50.00", 5, 0, 0),
                row("P40", "40.00", "50.00", 5, 0, 0),
                row("P25", "25.00", "50.00", 5, 0, 0),
                row("P50", "50.00", "50.00", 5, 0, 0),
                row("P30", "30.00", "50.00", 5, 0, 0)));

        MatchPreview preview = service().preview("summarisation", Money.of("20.00"));

        assertThat(preview.nearMisses()).extracting(AgentOption::agentName)
                .containsExactly("P25", "P30", "P40");              // cheapest 3, ascending
    }

    @Test
    void shortlistCappedAtFive() {
        List<ShortlistCandidateRow> six = List.of(
                row("a", "10.00", "50.00", 5, 0, 0), row("b", "11.00", "50.00", 5, 0, 0),
                row("c", "12.00", "50.00", 5, 0, 0), row("d", "13.00", "50.00", 5, 0, 0),
                row("e", "14.00", "50.00", 5, 0, 0), row("f", "15.00", "50.00", 5, 0, 0));
        when(queryPort.findBookableCandidates(anyString())).thenReturn(six);

        assertThat(service().preview("summarisation", Money.of("30.00")).shortlist()).hasSize(5);
    }

    @Test
    void availabilityIsFalseWhenAtOrOverCapacity() {
        when(queryPort.findBookableCandidates(anyString()))
                .thenReturn(List.of(row("Busy", "10.00", "50.00", 3, 3, 0)));  // inFlight == maxConcurrent

        assertThat(service().preview("summarisation", Money.of("20.00")).shortlist().get(0).available())
                .isFalse();
    }

    @Test
    void emptyWhenNoBookableCandidates() {
        when(queryPort.findBookableCandidates(anyString())).thenReturn(List.of());

        MatchPreview preview = service().preview("summarisation", Money.of("20.00"));

        assertThat(preview.shortlist()).isEmpty();
        assertThat(preview.nearMisses()).isEmpty();
    }

    @Test
    void categoryIsLowercasedBeforeRanking() {
        when(queryPort.findBookableCandidates(anyString()))
                .thenReturn(List.of(row("Agent", "10.00", "50.00", 5, 0, 0)));

        // Rows advertise "summarisation" (lowercase); a mixed-case input must still match after normalization.
        assertThat(service().preview("Summarisation", Money.of("20.00")).shortlist()).hasSize(1);
    }
}
