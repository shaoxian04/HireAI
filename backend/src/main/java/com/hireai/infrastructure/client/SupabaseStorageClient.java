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
        requireConfigured();
        String marker = "/storage/v1/object/public/" + bucket + "/";
        int idx = publicUrl == null ? -1 : publicUrl.indexOf(marker);
        if (idx < 0) {
            log.warn("Ignoring delete for unrecognised media URL: {}", publicUrl);
            return;
        }
        String objectKey = publicUrl.substring(idx + marker.length());
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

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url == null ? "" : url;
    }
}
