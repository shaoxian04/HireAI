package com.hireai.infrastructure.client;

import com.hireai.application.port.delivery.DeliverableFetchPort;
import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryFetch;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.utility.exception.DomainException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches an Agent's deliverable link once, for the DeliverySnapshot (D24), under the same Hard
 * Invariant #6 rules as every other Agent-facing request:
 * <ul>
 *   <li>HTTPS only; {@code http://localhost} only under {@code hireai.dispatch.allow-insecure-localhost};</li>
 *   <li>the shared SSRF guard ({@link WebhookUrlValidatorPort#assertAgentFileFetchable}) right before
 *       connecting, so a name that now resolves somewhere internal is never contacted;</li>
 *   <li>redirects are never followed (a 3xx is a failed fetch), as in {@link AgentDispatchRestClientConfig};</li>
 *   <li>bounded: connect timeout, a total time limit covering headers and body, and a byte limit
 *       (one byte past it and reading stops: {@link DeliveryOutcome.TooLarge}).</li>
 * </ul>
 * It never throws for a bad link; the reason goes to the Client as the {@code FILE_FETCHED} detail,
 * so it names what the link did, never internal addresses.
 *
 * <p>{@code hireai.delivery.trust-store} (optional, PKCS12) replaces the JDK's trust anchors; it
 * exists for local HTTPS file servers (tests) and private CAs, and is unset in production.
 */
@Slf4j
@Component
public class DeliverableFetchClient implements DeliverableFetchPort, DisposableBean {

    private final HttpClient http;
    private final WebhookUrlValidatorPort urlValidator;
    private final boolean allowInsecureLocalhost;
    private final Duration totalTimeout;
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();

    public DeliverableFetchClient(WebhookUrlValidatorPort urlValidator,
                                  @Value("${hireai.dispatch.allow-insecure-localhost:false}") boolean allowInsecureLocalhost,
                                  @Value("${hireai.delivery.connect-timeout:PT5S}") Duration connectTimeout,
                                  @Value("${hireai.delivery.timeout:PT30S}") Duration totalTimeout,
                                  @Value("${hireai.delivery.trust-store:}") String trustStore,
                                  @Value("${hireai.delivery.trust-store-password:}") String trustStorePassword) {
        this.urlValidator = urlValidator;
        this.allowInsecureLocalhost = allowInsecureLocalhost;
        this.totalTimeout = totalTimeout;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout);
        if (trustStore != null && !trustStore.isBlank()) {
            builder.sslContext(sslContextTrusting(Path.of(trustStore), trustStorePassword));
        }
        this.http = builder.build();
    }

    @Override
    public DeliveryFetch fetch(String url, long maxBytes) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return unavailable("the link is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(allowInsecureLocalhost && isHttpLocalhost(uri))) {
            return unavailable("the link is not HTTPS");
        }
        try {
            urlValidator.assertAgentFileFetchable(url);
        } catch (DomainException e) {
            log.info("Deliverable link refused by the SSRF guard: {}", e.getMessage());
            return unavailable("the link does not resolve to a public address the platform fetches from");
        }

        long deadline = System.nanoTime() + totalTimeout.toNanos();
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(totalTimeout)
                .header("Accept", "*/*").build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            return unavailable("the link did not answer within " + totalTimeout.toSeconds() + " seconds");
        } catch (IOException e) {
            return unavailable("the link could not be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("the fetch was interrupted");
        }

        InputStream body = response.body();
        try (body) {
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                return unavailable("the link answered a redirect (" + status + "), which the platform never follows");
            }
            if (status < 200 || status >= 300) {
                return unavailable("the link answered " + status);
            }
            OptionalLong declaredLength = response.headers().firstValueAsLong("Content-Length");
            if (declaredLength.isPresent() && declaredLength.getAsLong() > maxBytes) {
                return new DeliveryOutcome.TooLarge(maxBytes);
            }
            byte[] content = readWithin(body, maxBytes, deadline);
            if (content == null) {
                return new DeliveryOutcome.TooLarge(maxBytes);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            return new DeliveryFetch.Fetched(content, contentType);
        } catch (TimeoutException e) {
            return unavailable("the file did not finish downloading within " + totalTimeout.toSeconds() + " seconds");
        } catch (IOException | ExecutionException e) {
            return unavailable("the download broke off");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("the fetch was interrupted");
        }
    }

    /** The whole body, or null when it runs past {@code maxBytes}. Bounded by {@code deadline} (nanoTime). */
    private byte[] readWithin(InputStream body, long maxBytes, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException, IOException {
        Future<byte[]> read = readers.submit(() -> readCapped(body, maxBytes));
        try {
            return read.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            read.cancel(true);
            body.close();
            throw e;
        }
    }

    private static byte[] readCapped(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxBytes) {
                return null;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static DeliveryOutcome.Unavailable unavailable(String reason) {
        return new DeliveryOutcome.Unavailable(reason);
    }

    private static boolean isHttpLocalhost(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
    }

    private static SSLContext sslContextTrusting(Path trustStore, String password) {
        try (InputStream in = Files.newInputStream(trustStore)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, password == null ? new char[0] : password.toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Could not load hireai.delivery.trust-store " + trustStore, e);
        }
    }

    @Override
    public void destroy() {
        readers.shutdownNow();
        http.close();
    }
}
