package com.hireai.application.biz.offering.embedding.impl;

import com.hireai.application.biz.offering.embedding.TaskTextEmbeddingAppService;
import com.hireai.application.port.embedding.EmbeddingPort;
import com.hireai.application.port.embedding.EmbeddingUnavailableException;
import com.hireai.domain.biz.offering.embedding.info.TextVector;
import com.hireai.domain.biz.offering.embedding.model.TaskText;
import com.hireai.domain.biz.offering.embedding.repository.TextEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Non-transactional on purpose ({@code NOT_SUPPORTED}), like the Agent embedding service: the provider
 * call (up to its timeout) never holds a database transaction, and from the after-commit routing
 * listener the finished transaction's resources are suspended, so the cache insert commits on its own.
 * Any failure (provider or cache) degrades to "no vector": relevance is an optional signal.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class TaskTextEmbeddingAppServiceImpl implements TaskTextEmbeddingAppService {

    private final TextEmbeddingRepository textEmbeddingRepository;
    private final EmbeddingPort embeddingPort;
    private final Clock clock;

    @Override
    public Optional<TextVector> vectorFor(String title, String description) {
        TaskText text = TaskText.of(title, description);
        if (text.isBlank()) {
            return Optional.empty();
        }
        String model = embeddingPort.model();
        String hash = text.hash();
        try {
            Optional<TextVector> cached = textEmbeddingRepository.find(hash, model);
            if (cached.isPresent()) {
                return cached;
            }
            TextVector vector = new TextVector(model, embeddingPort.embed(text.text()));
            textEmbeddingRepository.saveIfAbsent(hash, vector, clock.instant());
            return Optional.of(vector);
        } catch (EmbeddingUnavailableException e) {
            log.warn("Task text not embedded ({}); matching without relevance", e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Task text vector unavailable; matching without relevance", e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<TextVector> cachedVectorFor(String title, String description) {
        TaskText text = TaskText.of(title, description);
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            return textEmbeddingRepository.find(text.hash(), embeddingPort.model());
        } catch (RuntimeException e) {
            log.warn("Cached task text vector unavailable; matching without relevance", e);
            return Optional.empty();
        }
    }
}
