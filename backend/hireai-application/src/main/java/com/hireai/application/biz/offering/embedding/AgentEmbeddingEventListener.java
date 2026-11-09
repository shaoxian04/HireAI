package com.hireai.application.biz.offering.embedding;

import com.hireai.domain.biz.offering.agent.event.AgentRegisteredDomainEvent;
import com.hireai.domain.biz.offering.storefront.event.AgentProfileUpdatedDomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Embeds an Agent's profile text once its registration or profile edit has COMMITTED, so the
 * Builder's change is saved whatever the provider does. Any failure is logged and swallowed: the
 * request still succeeds, and the re-embed sweeper fills the vector later.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AgentEmbeddingEventListener {

    private final AgentEmbeddingAppService agentEmbeddingAppService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAgentRegistered(AgentRegisteredDomainEvent event) {
        refresh(event.agentId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProfileUpdated(AgentProfileUpdatedDomainEvent event) {
        refresh(event.agentId());
    }

    private void refresh(UUID agentId) {
        try {
            agentEmbeddingAppService.refresh(agentId);
        } catch (RuntimeException e) {
            log.warn("Agent {} profile not embedded ({}); the re-embed sweeper will retry",
                    agentId, e.getMessage());
        }
    }
}
