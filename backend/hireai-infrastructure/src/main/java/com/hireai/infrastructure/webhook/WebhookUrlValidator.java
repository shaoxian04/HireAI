package com.hireai.infrastructure.webhook;

import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.webhook.IpClassifier;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Component
public class WebhookUrlValidator implements WebhookUrlValidatorPort {

    private final boolean allowInsecureLocalhost;

    public WebhookUrlValidator(@Value("${hireai.webhooks.allow-insecure-localhost:false}") boolean allowInsecureLocalhost) {
        this.allowInsecureLocalhost = allowInsecureLocalhost;
    }

    @Override
    public void assertDeliverable(String url) {
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
            resolved = InetAddress.getAllByName(host);
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
