package com.hireai.support.dns;

import com.hireai.infrastructure.webhook.HostResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A deterministic, offline DNS for the SSRF guard in every Spring test context. An IP literal
 * resolves to itself and {@code localhost} to loopback; any other name answers with a public
 * address ({@link #PUBLIC_ADDRESS}) unless a test pins it with {@link #answer}. Tests use
 * {@link #answer} to make a name resolve somewhere internal (DNS rebinding) and {@link #lookups}
 * to see which names the guard asked about.
 */
public class TestHostResolver implements HostResolver {

    public static final String PUBLIC_ADDRESS = "93.184.216.34";

    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final List<String> lookups = new CopyOnWriteArrayList<>();

    /** From now on {@code host} resolves to {@code address}. */
    public void answer(String host, String address) {
        answers.put(host.toLowerCase(), address);
    }

    public List<String> lookups() {
        return List.copyOf(lookups);
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        lookups.add(host);
        String pinned = answers.get(host.toLowerCase());
        if (pinned != null) {
            return new InetAddress[]{InetAddress.getByName(pinned)};
        }
        if ("localhost".equalsIgnoreCase(host) || isIpLiteral(host)) {
            return InetAddress.getAllByName(host); // no DNS query for a literal
        }
        return new InetAddress[]{InetAddress.getByName(PUBLIC_ADDRESS)};
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("[0-9.]+") || host.contains(":") || host.startsWith("[");
    }
}
