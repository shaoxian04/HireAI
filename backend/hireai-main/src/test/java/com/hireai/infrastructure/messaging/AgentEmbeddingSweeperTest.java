package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.offering.embedding.AgentEmbeddingAppService;
import com.hireai.application.port.embedding.EmbeddingUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEmbeddingSweeperTest {

    private final AgentEmbeddingAppService appService = mock(AgentEmbeddingAppService.class);
    private final AgentEmbeddingSweeper sweeper = new AgentEmbeddingSweeper(appService);
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    void aFailureForOneAgentDoesNotStopTheOthers() {
        when(appService.agentIdsNeedingEmbedding()).thenReturn(List.of(a, b, c));
        doThrow(new IllegalStateException("bad row")).when(appService).refresh(a);

        sweeper.sweep();

        verify(appService).refresh(b);
        verify(appService).refresh(c);
    }

    @Test
    void aProviderOutageEndsThePass() {
        when(appService.agentIdsNeedingEmbedding()).thenReturn(List.of(a, b, c));
        doThrow(new EmbeddingUnavailableException("down")).when(appService).refresh(b);

        sweeper.sweep();

        verify(appService).refresh(a);
        verify(appService, never()).refresh(c);
    }

    @Test
    void aListingFailureIsSwallowed() {
        when(appService.agentIdsNeedingEmbedding()).thenThrow(new IllegalStateException("db down"));

        sweeper.sweep();

        verify(appService, never()).refresh(a);
    }
}
