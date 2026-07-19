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
            || ip.isMulticastAddress();
    }
}
