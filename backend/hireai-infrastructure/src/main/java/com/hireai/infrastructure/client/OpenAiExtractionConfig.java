package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.port.extraction.RequirementExtractorPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Wires the requirement extractor from {@code hireai.extraction.*}; startup rejects a blank model or non-positive timeout. */
@Configuration
public class OpenAiExtractionConfig {

    @Bean
    public RequirementExtractorPort requirementExtractorPort(
            @Value("${hireai.extraction.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${hireai.extraction.api-key:}") String apiKey,
            @Value("${hireai.extraction.model:gpt-4o-mini}") String model,
            @Value("${hireai.extraction.timeout:PT10S}") Duration timeout,
            ObjectMapper objectMapper) {
        return OpenAiRequirementExtractor.create(baseUrl, apiKey, model, timeout, objectMapper);
    }
}
