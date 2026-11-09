package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hireai.application.port.embedding.EmbeddingPort;
import com.hireai.application.port.embedding.EmbeddingUnavailableException;
import com.hireai.domain.biz.offering.embedding.model.AgentEmbeddingModel;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * {@link EmbeddingPort} over the OpenAI embeddings API ({@code POST <baseUrl>/embeddings}). Every
 * failure (no API key, connection error, timeout, non-2xx, a malformed or wrongly sized vector)
 * becomes {@link EmbeddingUnavailableException}; callers carry on without a vector. A blank API
 * key never touches the network.
 */
public class OpenAiEmbeddingClient implements EmbeddingPort {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public OpenAiEmbeddingClient(RestClient restClient, String apiKey, String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("hireai.embedding.model must not be blank");
        }
        this.restClient = restClient;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    /**
     * A client on its own JDK {@link HttpClient} with {@code timeout} for both connect and read, so
     * a slow provider costs the caller at most about that long.
     */
    public static OpenAiEmbeddingClient create(String baseUrl, String apiKey, String model, Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("hireai.embedding.timeout must be positive, got " + timeout);
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return new OpenAiEmbeddingClient(
                RestClient.builder().baseUrl(base).requestFactory(factory).build(), apiKey, model);
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public float[] embed(String text) {
        if (apiKey.isEmpty()) {
            throw new EmbeddingUnavailableException("No embedding API key configured (OPENAI_API_KEY)");
        }
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "input", text, "dimensions", AgentEmbeddingModel.DIMENSIONS))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new EmbeddingUnavailableException("Embedding request failed: " + e.getMessage(), e);
        }
        return vectorFrom(response);
    }

    private static float[] vectorFrom(JsonNode response) {
        JsonNode embedding = response == null ? null : response.path("data").path(0).path("embedding");
        if (embedding == null || !embedding.isArray() || embedding.size() != AgentEmbeddingModel.DIMENSIONS) {
            throw new EmbeddingUnavailableException("Embedding response has no "
                    + AgentEmbeddingModel.DIMENSIONS + "-dimension vector");
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < vector.length; i++) {
            JsonNode value = embedding.get(i);
            if (!value.isNumber()) {
                throw new EmbeddingUnavailableException("Embedding response has a non-numeric component");
            }
            vector[i] = value.floatValue();
        }
        return vector;
    }
}
