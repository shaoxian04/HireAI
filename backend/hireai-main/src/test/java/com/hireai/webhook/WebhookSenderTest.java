package com.hireai.webhook;

import com.hireai.application.port.webhook.WebhookSendResult;
import com.hireai.infrastructure.webhook.WebhookSender;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WebhookSenderTest {

    private WebhookSender sender(MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        return new WebhookSender(builder);
    }

    @Test void postsSignedBodyAndReportsSuccessOn2xx() {
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        WebhookSender sender = sender(h);
        h[0].expect(requestTo("https://client.example.com/cb"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-HireAI-Signature", "t=1,v1=abc"))
            .andExpect(header("X-HireAI-Event-Id", "ev-1"))
            .andExpect(header("X-HireAI-Event-Type", "task.completed"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string("{\"a\":1}"))
            .andRespond(withSuccess());
        WebhookSendResult r = sender.send("https://client.example.com/cb", "{\"a\":1}", "t=1,v1=abc", "ev-1", "task.completed");
        assertThat(r.success()).isTrue();
        h[0].verify();
    }

    @Test void reportsFailureOn5xxWithoutThrowing() {
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        WebhookSender sender = sender(h);
        h[0].expect(requestTo("https://x/y")).andRespond(withServerError());
        WebhookSendResult r = sender.send("https://x/y", "{}", "t=1,v1=z", "ev-2", "task.failed");
        assertThat(r.success()).isFalse();
        assertThat(r.statusCode()).isEqualTo(500);
    }
}
