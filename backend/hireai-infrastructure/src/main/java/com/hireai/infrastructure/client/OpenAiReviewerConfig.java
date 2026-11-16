package com.hireai.infrastructure.client;

import com.hireai.application.port.aireview.AiReviewerPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Wires the AI Review reviewer from {@code hireai.ai-review.*} (spec D37: the arbitrator's provider by default). */
@Configuration
public class OpenAiReviewerConfig {

    @Bean
    public AiReviewerPort aiReviewerPort(
            @Value("${hireai.ai-review.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${hireai.ai-review.api-key:}") String apiKey,
            @Value("${hireai.ai-review.model:gpt-4o}") String model,
            @Value("${hireai.ai-review.timeout:PT60S}") Duration timeout,
            @Value("${hireai.ai-review.media-max-bytes:8388608}") long mediaMaxBytes) {
        return OpenAiReviewerClient.create(baseUrl, apiKey, model, timeout, mediaMaxBytes);
    }
}
