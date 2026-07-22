package com.hireai.webhook;

import com.hireai.domain.biz.webhook.IpClassifier;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import static org.assertj.core.api.Assertions.assertThat;

class IpClassifierTest {
    private InetAddress ip(String s) throws Exception { return InetAddress.getByName(s); } // literal → no DNS

    @Test void blocksLoopbackPrivateLinkLocalAndMetadata() throws Exception {
        for (String bad : new String[]{"127.0.0.1","10.0.0.5","172.16.0.1","192.168.1.1",
                "169.254.169.254","0.0.0.0","::1","224.0.0.1","ff02::1", // last two: IPv4/IPv6 multicast
                "100.64.0.1","100.127.255.255", // IPv4 CGNAT 100.64.0.0/10
                "fc00::1","fd12:3456::1"}) { // IPv6 Unique-Local (ULA) fc00::/7
            assertThat(IpClassifier.isBlocked(ip(bad))).as(bad).isTrue();
        }
    }
    @Test void allowsPublicAddresses() throws Exception {
        assertThat(IpClassifier.isBlocked(ip("93.184.216.34"))).isFalse(); // example.com range
        assertThat(IpClassifier.isBlocked(ip("8.8.8.8"))).isFalse();
        assertThat(IpClassifier.isBlocked(ip("100.63.255.255"))).isFalse(); // just below CGNAT
        assertThat(IpClassifier.isBlocked(ip("100.128.0.1"))).isFalse();    // just above CGNAT
        assertThat(IpClassifier.isBlocked(ip("2001:4860:4860::8888"))).isFalse(); // public IPv6 (Google DNS)
    }
}
