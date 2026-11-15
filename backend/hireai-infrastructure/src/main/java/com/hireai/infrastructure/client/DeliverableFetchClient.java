package com.hireai.infrastructure.client;

import com.hireai.application.port.delivery.DeliverableFetchPort;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryFetch;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.infrastructure.webhook.WebhookUrlValidator;
import com.hireai.utility.exception.DomainException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches an Agent's deliverable link once, for the DeliverySnapshot (D24), under Hard Invariant #6:
 * <ul>
 *   <li><b>Pinned DNS.</b> The host is resolved once, by the SSRF guard
 *       ({@link WebhookUrlValidator#vetAgentFileHost}), and the connection goes to exactly those
 *       vetted addresses through Apache HttpClient's {@link DnsResolver} hook: there is no second
 *       lookup an attacker could rebind to an internal address. TLS still uses the original host
 *       name for SNI and certificate hostname verification. (The Agent picks this URL and the bytes
 *       are stored and shown, so this is a full-read SSRF surface, unlike the webhook sends, whose
 *       rebinding gap is documented on {@code WebhookUrlValidatorPort}.)</li>
 *   <li>HTTPS only; {@code localhost} only under {@code hireai.dispatch.allow-insecure-localhost}.</li>
 *   <li>Redirects are never followed (a 3xx is a failed fetch), no automatic retries, and no
 *       content decompression (the bytes kept are the bytes sent; no decompression bombs).</li>
 *   <li>Bounded: connect timeout, a total time limit over headers and body, and a byte limit (one
 *       byte past it and reading stops: {@link DeliveryOutcome.TooLarge}).</li>
 * </ul>
 * It never throws for a bad link; the reason becomes the Client-visible {@code FILE_FETCHED}
 * detail, so it describes what the link did and never names internal addresses.
 *
 * <p>{@code hireai.delivery.trust-store} (optional, PKCS12) replaces the JDK's trust anchors; it
 * exists for local HTTPS file servers (tests) and private CAs, and is unset in production.
 */
@Slf4j
@Component
public class DeliverableFetchClient implements DeliverableFetchPort, DisposableBean {

    private final WebhookUrlValidator guard;
    private final Duration connectTimeout;
    private final Duration totalTimeout;
    private final SSLContext sslContext;
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();

    public DeliverableFetchClient(WebhookUrlValidator guard,
                                  @Value("${hireai.delivery.connect-timeout:PT5S}") Duration connectTimeout,
                                  @Value("${hireai.delivery.timeout:PT30S}") Duration totalTimeout,
                                  @Value("${hireai.delivery.trust-store:}") String trustStore,
                                  @Value("${hireai.delivery.trust-store-password:}") String trustStorePassword) {
        this.guard = guard;
        this.connectTimeout = connectTimeout;
        this.totalTimeout = totalTimeout;
        this.sslContext = trustStore == null || trustStore.isBlank()
                ? defaultSslContext()
                : sslContextTrusting(Path.of(trustStore), trustStorePassword);
    }

    @Override
    public DeliveryFetch fetch(String url, long maxBytes) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return unavailable("the link is not a valid URL");
        }
        List<InetAddress> vetted;
        try {
            vetted = guard.vetAgentFileHost(uri);
        } catch (DomainException e) {
            log.info("Deliverable link refused by the SSRF guard: {}", e.getMessage());
            return unavailable("https".equalsIgnoreCase(uri.getScheme())
                    ? "the link does not resolve to a public address the platform fetches from"
                    : "the link is not HTTPS");
        }

        long deadline = System.nanoTime() + totalTimeout.toNanos();
        HttpGet get = new HttpGet(uri);
        get.setHeader("Accept", "*/*");
        try (CloseableHttpClient http = pinnedClient(uri.getHost(), vetted)) {
            Future<DeliveryFetch> exchange = readers.submit(() -> exchange(http, uri, get, maxBytes));
            try {
                return exchange.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                get.cancel();
                exchange.cancel(true);
                return unavailable("the file did not finish downloading within " + totalTimeout.toSeconds() + " seconds");
            } catch (ExecutionException e) {
                return unavailable(describe(e.getCause()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("the fetch was interrupted");
        } catch (IOException e) {
            return unavailable("the link could not be reached");
        }
    }

    private DeliveryFetch exchange(CloseableHttpClient http, URI uri, HttpGet get, long maxBytes) throws IOException {
        HttpHost target = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort());
        try (ClassicHttpResponse response = http.executeOpen(target, get, null)) {
            int status = response.getCode();
            if (status >= 300 && status < 400) {
                return unavailable("the link answered a redirect (" + status + "), which the platform never follows");
            }
            if (status < 200 || status >= 300) {
                return unavailable("the link answered " + status);
            }
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                return new DeliveryFetch.Fetched(new byte[0], null);
            }
            if (entity.getContentLength() > maxBytes) {
                return new DeliveryOutcome.TooLarge(maxBytes);
            }
            byte[] content;
            try (InputStream in = entity.getContent()) {
                content = readCapped(in, maxBytes);
            }
            if (content == null) {
                return new DeliveryOutcome.TooLarge(maxBytes);
            }
            Header type = response.getFirstHeader("Content-Type");
            return new DeliveryFetch.Fetched(content, type == null ? null : type.getValue());
        }
    }

    /** A one-off client whose only DNS answer is {@code vetted}, for {@code host} alone. */
    private CloseableHttpClient pinnedClient(String host, List<InetAddress> vetted) {
        InetAddress[] pinned = vetted.toArray(InetAddress[]::new);
        DnsResolver pinnedDns = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String name) throws UnknownHostException {
                if (!host.equalsIgnoreCase(name)) {
                    throw new UnknownHostException("Only the vetted host may be contacted: " + name);
                }
                return pinned.clone();
            }

            @Override
            public String resolveCanonicalHostname(String name) {
                return name;
            }
        };
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinnedDns)
                .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create().setSslContext(sslContext).build())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(connectTimeout))
                        .setSocketTimeout(Timeout.of(totalTimeout))
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(totalTimeout)).build())
                .build();
    }

    private static String describe(Throwable cause) {
        if (cause instanceof SocketTimeoutException || cause instanceof InterruptedIOException) {
            return "the link did not answer in time";
        }
        return cause instanceof IOException ? "the link could not be reached" : "the download failed";
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

    private static SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException("No default TLS context", e);
        }
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
    }
}
