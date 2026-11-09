package com.hireai.infrastructure.client;

import com.hireai.application.port.embedding.EmbeddingPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Wires the OpenAI embedding client from {@code hireai.embedding.*}; startup rejects a blank model or non-positive timeout. */
@Configuration
public class OpenAiEmbeddingConfig {

    @Bean
    public EmbeddingPort embeddingPort(
            @Value("${hireai.embedding.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${hireai.embedding.api-key:}") String apiKey,
            @Value("${hireai.embedding.model:text-embedding-3-small}") String model,
            @Value("${hireai.embedding.timeout:PT2S}") Duration timeout) {
        return OpenAiEmbeddingClient.create(baseUrl, apiKey, model, timeout);
    }
}
