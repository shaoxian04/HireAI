package com.hireai.infrastructure.webhook;

import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.identity.webhooksubscription.IpClassifier;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/**
 * The one SSRF guard for outbound webhooks, used for client webhook callbacks and Agent webhook
 * URLs alike. Each kind has its own {@code http://localhost} dev opt-in; everything else must be
 * HTTPS and resolve only to public addresses ({@link IpClassifier}).
 */
@Component
public class WebhookUrlValidator implements WebhookUrlValidatorPort {

    private final boolean allowInsecureLocalhostWebhooks;
    private final boolean allowInsecureLocalhostAgents;
    private final HostResolver hostResolver;

    @Autowired
    public WebhookUrlValidator(
            @Value("${hireai.webhooks.allow-insecure-localhost:false}") boolean allowInsecureLocalhostWebhooks,
            @Value("${hireai.dispatch.allow-insecure-localhost:false}") boolean allowInsecureLocalhostAgents,
            HostResolver hostResolver) {
        this.allowInsecureLocalhostWebhooks = allowInsecureLocalhostWebhooks;
        this.allowInsecureLocalhostAgents = allowInsecureLocalhostAgents;
        this.hostResolver = hostResolver;
    }

    /** Client-webhook-only validator on the system resolver (unit tests). */
    public WebhookUrlValidator(boolean allowInsecureLocalhostWebhooks) {
        this(allowInsecureLocalhostWebhooks, false, HostResolver.SYSTEM);
    }

    @Override
    public void assertDeliverable(String url) {
        check(url, allowInsecureLocalhostWebhooks);
    }

    @Override
    public void assertAgentWebhookDeliverable(String url) {
        check(url, allowInsecureLocalhostAgents);
    }

    /**
     * The SSRF guard for an Agent's deliverable link (the DeliverySnapshot fetch, D24), returning
     * the addresses it vetted so the caller connects to exactly those and never resolves the name
     * again (no DNS-rebinding window, unlike the webhook paths' documented gap). HTTPS only; under
     * {@code hireai.dispatch.allow-insecure-localhost} a {@code localhost}/{@code 127.0.0.1} host
     * (http or https) is allowed and pinned to loopback. Throws VALIDATION_ERROR otherwise.
     */
    public List<InetAddress> vetAgentFileHost(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Link must be an absolute https URL");
        }
        boolean localhost = "localhost".equals(host) || "127.0.0.1".equals(host);
        if (allowInsecureLocalhostAgents && localhost
                && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            return List.of(InetAddress.getLoopbackAddress()); // the dispatch dev opt-in
        }
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Link must use HTTPS");
        }
        InetAddress[] resolved;
        try {
            resolved = hostResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Link host does not resolve");
        }
        if (resolved == null || resolved.length == 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Link host does not resolve");
        }
        for (InetAddress ip : resolved) {
            if (IpClassifier.isBlocked(ip)) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, "Link resolves to a private/blocked address");
            }
        }
        return List.of(resolved);
    }

    private void check(String url, boolean allowInsecureLocalhost) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Malformed callback URL");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Callback URL must be an absolute http(s) URL");
        }
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean devLocalhost = allowInsecureLocalhost && "http".equalsIgnoreCase(scheme)
                && ("localhost".equals(host) || "127.0.0.1".equals(host));
        if (!https && !devLocalhost) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Callback URL must use HTTPS");
        }
        if (devLocalhost) return; // explicit dev opt-in

        InetAddress[] resolved;
        try {
            resolved = hostResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Callback host does not resolve");
        }
        for (InetAddress ip : resolved) {
            if (IpClassifier.isBlocked(ip)) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, "Callback URL resolves to a private/blocked address");
            }
        }
    }
}
