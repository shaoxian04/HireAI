package com.hireai.infrastructure.client;

import com.hireai.application.port.storage.MediaStoragePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Supabase Storage REST adapter. Object layout: {bucket}/agents/{agentId}/{kind}-{uuid}.{ext}.
 * Upload = POST /storage/v1/object/{bucket}/{key} (x-upsert) with the service key; public read
 * URL = /storage/v1/object/public/{bucket}/{key} (bucket is public-read, write is server-only).
 */
@Component
@Slf4j
public class SupabaseStorageClient implements MediaStoragePort {

    private final RestClient restClient;
    private final String baseUrl;
    private final String serviceKey;
    private final String bucket;

    public SupabaseStorageClient(RestClient.Builder restClientBuilder,
                                 @Value("${hireai.storage.supabase-url:}") String baseUrl,
                                 @Value("${hireai.storage.service-key:}") String serviceKey,
                                 @Value("${hireai.storage.bucket:agent-media}") String bucket) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.serviceKey = serviceKey;
        this.bucket = bucket;
    }

    @Override
    public String upload(String objectKey, String contentType, byte[] bytes) {
        requireConfigured();
        validateObjectKey(objectKey);
        restClient.post()
                .uri(URI.create(baseUrl + "/storage/v1/object/" + bucket + "/" + objectKey))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceKey)
                .header("x-upsert", "true")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes)
                .retrieve()
                .toBodilessEntity();
        return publicUrl(objectKey);
    }

    @Override
    public void deleteByUrl(String publicUrl) {
        // Fail loudly on misconfiguration even for a best-effort op.
        requireConfigured();
        String marker = "/storage/v1/object/public/" + bucket + "/";
        String expectedPrefix = baseUrl + marker;
        if (publicUrl == null || !publicUrl.startsWith(expectedPrefix)) {
            log.warn("Ignoring delete: URL does not match configured storage base: {}", publicUrl);
            return;
        }
        String objectKey = publicUrl.substring(expectedPrefix.length());
        validateObjectKey(objectKey);
        try {
            restClient.delete()
                    .uri(URI.create(baseUrl + "/storage/v1/object/" + bucket + "/" + objectKey))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Best-effort media delete failed for {}: {}", objectKey, ex.getMessage());
        }
    }

    private String publicUrl(String objectKey) {
        return baseUrl + "/storage/v1/object/public/" + bucket + "/" + objectKey;
    }

    private void requireConfigured() {
        if (baseUrl.isBlank() || serviceKey.isBlank()) {
            throw new IllegalStateException(
                    "Supabase storage is not configured (SUPABASE_URL / SUPABASE_SERVICE_KEY)");
        }
    }

    private static void validateObjectKey(String key) {
        if (key == null || key.isBlank() || key.contains("..") || key.startsWith("/")) {
            throw new IllegalArgumentException("Invalid object key: " + key);
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
