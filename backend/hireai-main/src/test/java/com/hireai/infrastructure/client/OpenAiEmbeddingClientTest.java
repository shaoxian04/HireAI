package com.hireai.infrastructure.client;

import com.hireai.application.port.embedding.EmbeddingUnavailableException;
import com.hireai.support.embedding.FakeEmbeddingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The OpenAI embedding client against a local fake endpoint (no Docker, never the real API). */
class OpenAiEmbeddingClientTest {

    private static final String MODEL = "text-embedding-3-small";

    private final FakeEmbeddingProvider provider = FakeEmbeddingProvider.start();

    @AfterEach
    void stop() {
        provider.close();
    }

    private OpenAiEmbeddingClient client(String apiKey, Duration timeout) {
        return OpenAiEmbeddingClient.create(provider.baseUrl() + "/", apiKey, MODEL, timeout);
    }

    @Test
    void returnsTheProvidersVectorForTheText() {
        provider.vectorFor(text -> FakeEmbeddingProvider.constant(0.5f));

        float[] vector = client("sk-test", Duration.ofSeconds(2)).embed("Summarise contracts");

        assertThat(vector).hasSize(1536).containsOnly(0.5f);
        assertThat(provider.requests()).singleElement().satisfies(r -> {
            assertThat(r.input()).isEqualTo("Summarise contracts");
            assertThat(r.model()).isEqualTo(MODEL);
            assertThat(r.authorization()).isEqualTo("Bearer sk-test");
        });
    }

    @Test
    void withoutAnApiKeyItFailsWithoutCallingTheProvider() {
        assertThatThrownBy(() -> client(" ", Duration.ofSeconds(2)).embed("text"))
                .isInstanceOf(EmbeddingUnavailableException.class);
        assertThat(provider.requests()).isEmpty();
    }

    @Test
    void anErrorStatusIsUnavailable() {
        provider.down();

        assertThatThrownBy(() -> client("sk-test", Duration.ofSeconds(2)).embed("text"))
                .isInstanceOf(EmbeddingUnavailableException.class);
    }

    @Test
    void aSlowProviderTimesOut() {
        provider.hanging();

        long started = System.nanoTime();
        assertThatThrownBy(() -> client("sk-test", Duration.ofMillis(300)).embed("text"))
                .isInstanceOf(EmbeddingUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aVectorOfTheWrongSizeIsUnavailable() {
        provider.vectorFor(text -> new float[]{0.1f, 0.2f});

        assertThatThrownBy(() -> client("sk-test", Duration.ofSeconds(2)).embed("text"))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining("1536");
    }

    @Test
    void startupRejectsANonPositiveTimeoutAndABlankModel() {
        assertThatThrownBy(() -> client("sk-test", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenAiEmbeddingClient.create(provider.baseUrl(), "k", " ", Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
