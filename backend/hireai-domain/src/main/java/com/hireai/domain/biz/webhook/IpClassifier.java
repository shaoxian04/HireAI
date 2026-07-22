package com.hireai.domain.biz.webhook;

import java.net.InetAddress;

/** Pure SSRF address rules: true if an IP must never be a webhook target. */
public final class IpClassifier {
    private IpClassifier() {}

    public static boolean isBlocked(InetAddress ip) {
        return ip.isAnyLocalAddress()      // 0.0.0.0, ::
            || ip.isLoopbackAddress()      // 127/8, ::1
            || ip.isLinkLocalAddress()     // 169.254/16 (incl. metadata), fe80::/10
            || ip.isSiteLocalAddress()     // 10/8, 172.16/12, 192.168/16
            || ip.isMulticastAddress()
            || isCgnatOrUla(ip);           // 100.64.0.0/10 (CGNAT), fc00::/7 (IPv6 ULA) — not covered by the above
    }

    /**
     * Java's {@link InetAddress} predicates don't cover IPv4 CGNAT (RFC 6598) or modern IPv6
     * Unique-Local addresses (RFC 4193) — {@code isSiteLocalAddress()} only recognizes the
     * deprecated {@code fec0::/10} range, not {@code fc00::/7}. Both are non-public and must
     * never be a legitimate webhook target, so they're classified directly from the raw bytes.
     */
    private static boolean isCgnatOrUla(InetAddress ip) {
        byte[] a = ip.getAddress();
        if (a.length == 4) {                 // IPv4 CGNAT 100.64.0.0/10
            return (a[0] & 0xFF) == 100 && (a[1] & 0xFF) >= 64 && (a[1] & 0xFF) <= 127;
        }
        if (a.length == 16) {                // IPv6 ULA fc00::/7
            return (a[0] & 0xFE) == 0xFC;
        }
        return false;
    }
}
