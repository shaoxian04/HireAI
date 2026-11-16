package com.hireai.support.aireview;

import com.hireai.application.port.aireview.AiReviewerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Points every Spring test context (picked up by component scanning, like {@code TestEmbeddingConfig})
 * at the {@link ScriptedAiReviewer}, so no test reaches a model even with {@code OPENAI_API_KEY} set.
 */
@Configuration
public class TestAiReviewerConfig {

    @Bean
    @Primary
    public AiReviewerPort testAiReviewerPort() {
        return ScriptedAiReviewer.shared();
    }
}
