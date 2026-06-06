package com.hireai.storage;

import com.hireai.infrastructure.client.SupabaseStorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupabaseStorageClientTest {

    private MockRestServiceServer server;
    private SupabaseStorageClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new SupabaseStorageClient(RestClient.builder(restTemplate),
                "https://proj.supabase.co", "service-key-123", "agent-media");
    }

    @Test
    void uploadPutsObjectAndReturnsPublicUrl() {
        server.expect(requestTo("https://proj.supabase.co/storage/v1/object/agent-media/agents/a1/logo-x.png"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer service-key-123"))
                .andExpect(header("x-upsert", "true"))
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andRespond(withSuccess());

        String url = client.upload("agents/a1/logo-x.png", "image/png", new byte[]{1, 2, 3});

        assertThat(url).isEqualTo(
                "https://proj.supabase.co/storage/v1/object/public/agent-media/agents/a1/logo-x.png");
        server.verify();
    }

    @Test
    void deleteByUrlDerivesObjectKey() {
        server.expect(requestTo("https://proj.supabase.co/storage/v1/object/agent-media/agents/a1/logo-x.png"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer service-key-123"))
                .andRespond(withSuccess());

        client.deleteByUrl("https://proj.supabase.co/storage/v1/object/public/agent-media/agents/a1/logo-x.png");
        server.verify();
    }

    @Test
    void uploadThrowsWhenUnconfigured() {
        // Blank url and key — storage not configured
        SupabaseStorageClient unconfigured = new SupabaseStorageClient(
                RestClient.builder(), "", "", "agent-media");

        assertThatThrownBy(() -> unconfigured.upload("agents/a1/logo.png", "image/png", new byte[]{1}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void deleteByUrlIgnoresForeignUrl() {
        // A URL that does not contain the public marker for this bucket — no HTTP call, no exception.
        // server has zero expectations; server.verify() confirms nothing was sent.
        MockRestServiceServer freshServer;
        RestTemplate restTemplate = new RestTemplate();
        freshServer = MockRestServiceServer.bindTo(restTemplate).build();
        SupabaseStorageClient localClient = new SupabaseStorageClient(
                RestClient.builder(restTemplate),
                "https://proj.supabase.co", "service-key-123", "agent-media");

        // No exception, no HTTP call
        localClient.deleteByUrl("https://other.cdn.example.com/images/logo.png");

        freshServer.verify(); // zero expectations — passes only if no request was made
    }
}
