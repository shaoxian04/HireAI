package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.offering.embedding.AgentEmbeddingAppService;
import com.hireai.application.port.embedding.EmbeddingUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Re-embed sweeper: every tick, (re)embeds each Agent whose profile vector is missing, was made
 * from older profile text, or came from another model (after a model change). This is what heals a
 * vector the provider failed to produce at registration or on a profile edit.
 *
 * <p>The first provider failure ends the pass, so an outage costs one timeout per tick rather than
 * one per Agent; any other per-Agent failure is logged and the pass moves on. Writes are idempotent
 * upserts, so overlapping instances would only repeat work (no single-instance assumption needed).
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class AgentEmbeddingSweeper {

    private final AgentEmbeddingAppService agentEmbeddingAppService;

    @Scheduled(fixedDelayString = "${hireai.embedding.sweep-interval:PT5M}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: one pass over every Agent that needs a vector. */
    void sweep() {
        List<UUID> pending;
        try {
            pending = agentEmbeddingAppService.agentIdsNeedingEmbedding();
        } catch (Exception e) {
            log.warn("Re-embed sweeper: could not list Agents", e);
            return;
        }
        int done = 0;
        for (UUID id : pending) {
            try {
                agentEmbeddingAppService.refresh(id);
                done++;
            } catch (EmbeddingUnavailableException e) {
                log.warn("Re-embed sweeper: provider unavailable ({}); {} Agent(s) left for the next pass",
                        e.getMessage(), pending.size() - done);
                return;
            } catch (Exception e) {
                log.warn("Re-embed sweeper: failed for Agent {}", id, e);
            }
        }
        if (done > 0) {
            log.info("Re-embed sweeper: embedded {} Agent(s)", done);
        }
    }
}
