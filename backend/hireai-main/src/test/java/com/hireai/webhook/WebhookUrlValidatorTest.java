package com.hireai.webhook;

import com.hireai.infrastructure.webhook.WebhookUrlValidator;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class WebhookUrlValidatorTest {
    private final WebhookUrlValidator v = new WebhookUrlValidator(false); // allow-insecure-localhost=false

    @Test void rejectsNonHttps() {
        assertThatThrownBy(() -> v.assertDeliverable("http://example.com/cb")).isInstanceOf(DomainException.class);
    }
    @Test void rejectsPrivateAndLoopbackHosts() {
        assertThatThrownBy(() -> v.assertDeliverable("https://127.0.0.1/cb")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> v.assertDeliverable("https://10.0.0.5/cb")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> v.assertDeliverable("https://169.254.169.254/latest/meta-data"))
                .isInstanceOf(DomainException.class);
    }
    @Test void rejectsGarbage() {
        assertThatThrownBy(() -> v.assertDeliverable("not a url")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> v.assertDeliverable("https://")).isInstanceOf(DomainException.class);
    }
    @Test void allowsAPublicHttpsHost() {
        // example.com resolves to public IPs; if the CI sandbox blocks DNS, swap for a literal public IP host.
        assertThatCode(() -> v.assertDeliverable("https://example.com/cb")).doesNotThrowAnyException();
    }
    @Test void allowsHttpsLiteralPublicIp() {
        assertThatCode(() -> v.assertDeliverable("https://93.184.216.34/cb")).doesNotThrowAnyException();
    }
    @Test void rejectsCgnatLiteralIp() {
        assertThatThrownBy(() -> v.assertDeliverable("https://100.64.0.1/cb")).isInstanceOf(DomainException.class);
    }
}
