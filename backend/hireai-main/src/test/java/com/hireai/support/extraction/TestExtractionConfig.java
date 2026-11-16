package com.hireai.support.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.port.extraction.RequirementExtractorPort;
import com.hireai.infrastructure.client.OpenAiRequirementExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Points the requirement extractor of every Spring test context (picked up by component scanning,
 * like {@code TestEmbeddingConfig}) at the JVM-wide {@link FakeExtractionProvider}, so no test ever
 * calls a real LLM, even with {@code OPENAI_API_KEY} set. A short timeout keeps the "hanging" case fast.
 */
@Configuration
public class TestExtractionConfig {

    @Bean
    @Primary
    public RequirementExtractorPort testRequirementExtractorPort(
            @Value("${hireai.extraction.model:gpt-4o-mini}") String model,
            ObjectMapper objectMapper) {
        return OpenAiRequirementExtractor.create(FakeExtractionProvider.shared().baseUrl(), "test-extraction-key",
                model, Duration.ofSeconds(2), objectMapper);
    }
}
