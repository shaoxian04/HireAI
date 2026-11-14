package com.hireai.application.biz.offering.embedding.impl;

import com.hireai.application.biz.offering.embedding.AgentEmbeddingAppService;
import com.hireai.application.port.embedding.EmbeddingPort;
import com.hireai.domain.biz.offering.embedding.info.AgentEmbeddingSource;
import com.hireai.domain.biz.offering.embedding.model.AgentEmbeddingModel;
import com.hireai.domain.biz.offering.embedding.model.AgentProfileText;
import com.hireai.domain.biz.offering.embedding.repository.AgentEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Non-transactional on purpose ({@code NOT_SUPPORTED}): the provider call (up to its timeout) must
 * never hold a database transaction, and when called from an after-commit listener the finished
 * transaction's resources are suspended, so the single-statement upsert commits on its own.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentEmbeddingAppServiceImpl implements AgentEmbeddingAppService {

    private final AgentEmbeddingRepository agentEmbeddingRepository;
    private final EmbeddingPort embeddingPort;
    private final Clock clock;

    @Override
    public List<UUID> agentIdsNeedingEmbedding() {
        String model = embeddingPort.model();
        return agentEmbeddingRepository.findSourcesOfLiveAgents().stream()
                .filter(source -> source.needsEmbedding(model))
                .map(AgentEmbeddingSource::agentId)
                .toList();
    }

    @Override
    public void refresh(UUID agentId) {
        Optional<AgentEmbeddingSource> source = agentEmbeddingRepository.findSource(agentId);
        String model = embeddingPort.model();
        if (source.isEmpty() || !source.get().needsEmbedding(model)) {
            return;
        }
        AgentProfileText text = source.get().profileText();
        float[] vector = embeddingPort.embed(text.text());
        agentEmbeddingRepository.save(AgentEmbeddingModel.of(agentId, model, text, vector, clock.instant()));
        log.info("Agent {} profile embedded with {}", agentId, model);
    }
}
