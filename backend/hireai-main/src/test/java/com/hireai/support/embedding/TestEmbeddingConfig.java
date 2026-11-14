package com.hireai.support.embedding;

import com.hireai.application.port.embedding.EmbeddingPort;
import com.hireai.infrastructure.client.OpenAiEmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Points the embedding client of every Spring test context (picked up by component scanning, like
 * {@code TestHostResolverConfig}) at the JVM-wide {@link FakeEmbeddingProvider}, so no test ever
 * calls OpenAI, even with {@code OPENAI_API_KEY} set in the environment. It is the real
 * {@link OpenAiEmbeddingClient}, so the HTTP contract and the timeout are exercised too.
 */
@Configuration
public class TestEmbeddingConfig {

    @Bean
    @Primary
    public EmbeddingPort testEmbeddingPort(
            @Value("${hireai.embedding.model:text-embedding-3-small}") String model,
            @Value("${hireai.embedding.timeout:PT2S}") Duration timeout) {
        return OpenAiEmbeddingClient.create(FakeEmbeddingProvider.shared().baseUrl(), "test-embedding-key",
                model, timeout);
    }
}
