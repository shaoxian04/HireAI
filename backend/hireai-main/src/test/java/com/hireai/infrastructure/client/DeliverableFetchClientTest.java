package com.hireai.infrastructure.client;

import com.hireai.domain.biz.acceptance.delivery.model.DeliveryFetch;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.infrastructure.webhook.HostResolver;
import com.hireai.infrastructure.webhook.WebhookUrlValidator;
import com.hireai.support.delivery.FakeFileHost;
import com.hireai.support.delivery.TestTls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The deliverable fetch obeys Hard Invariant #6: HTTPS, the SSRF guard, no redirects, bounded. */
class DeliverableFetchClientTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    private final FakeFileHost host = FakeFileHost.start();

    @AfterEach
    void stop() {
        host.close();
    }

    /** The dev opt-in on, as in the whole-app harness: https://localhost skips the DNS check. */
    private DeliverableFetchClient client(boolean devLocalhost, HostResolver dns) {
        return new DeliverableFetchClient(new WebhookUrlValidator(false, devLocalhost, dns), devLocalhost,
                Duration.ofSeconds(2), Duration.ofSeconds(5), TestTls.trustStore().toString(), TestTls.PASSWORD);
    }

    private DeliverableFetchClient client() {
        return client(true, HostResolver.SYSTEM);
    }

    @Test
    void fetchesTheWholeFileWithItsDeclaredType() {
        host.serve("/a.png", PNG, "image/png");

        DeliveryFetch fetch = client().fetch(host.url("/a.png"), 1000);

        assertThat(fetch).isInstanceOfSatisfying(DeliveryFetch.Fetched.class, f -> {
            assertThat(f.content()).isEqualTo(PNG);
            assertThat(f.contentType()).isEqualTo("image/png");
        });
    }

    @Test
    void aFileAtTheLimitIsFetchedAndOnePastItIsTooLarge() {
        host.serve("/a.png", PNG, "image/png");

        assertThat(client().fetch(host.url("/a.png"), PNG.length)).isInstanceOf(DeliveryFetch.Fetched.class);
        assertThat(client().fetch(host.url("/a.png"), PNG.length - 1))
                .isEqualTo(new DeliveryOutcome.TooLarge(PNG.length - 1));
    }

    @Test
    void aNon2xxAnswerIsUnavailableWithItsStatus() {
        host.fail("/gone", 404);

        assertThat(client().fetch(host.url("/gone"), 1000)).isInstanceOfSatisfying(DeliveryOutcome.Unavailable.class,
                u -> assertThat(u.reason()).contains("404"));
    }

    @Test
    void aRedirectIsNeverFollowed() {
        host.redirect("/moved", host.url("/target")).serve("/target", "x".getBytes(StandardCharsets.UTF_8), "text/plain");

        DeliveryFetch fetch = client().fetch(host.url("/moved"), 1000);

        assertThat(fetch).isInstanceOfSatisfying(DeliveryOutcome.Unavailable.class,
                u -> assertThat(u.reason()).contains("redirect"));
        assertThat(host.requests()).containsExactly("/moved");
    }

    @Test
    void withoutTheDevOptInLocalhostIsRefusedBeforeAnyConnection() {
        host.serve("/a.png", PNG, "image/png");

        DeliveryFetch fetch = client(false, HostResolver.SYSTEM).fetch(host.url("/a.png"), 1000);

        assertThat(fetch).isInstanceOf(DeliveryOutcome.Unavailable.class);
        assertThat(host.requests()).isEmpty();
    }

    @Test
    void aNameResolvingToAPrivateAddressIsRefused() {
        HostResolver internal = name -> new InetAddress[]{InetAddress.getByName("10.0.0.7")};

        DeliveryFetch fetch = client(true, internal).fetch("https://files.agent.example/a.png", 1000);

        assertThat(fetch).isInstanceOfSatisfying(DeliveryOutcome.Unavailable.class,
                u -> assertThat(u.reason()).contains("public address").doesNotContain("10.0.0.7"));
    }

    @Test
    void aPlainHttpLinkIsRefused() {
        DeliveryFetch fetch = client(false, HostResolver.SYSTEM).fetch("http://files.agent.example/a.png", 1000);

        assertThat(fetch).isInstanceOfSatisfying(DeliveryOutcome.Unavailable.class,
                u -> assertThat(u.reason()).contains("not HTTPS"));
    }
}
