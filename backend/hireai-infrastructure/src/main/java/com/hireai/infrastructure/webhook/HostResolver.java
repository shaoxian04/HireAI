package com.hireai.infrastructure.webhook;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves a host name to every address it answers with. The SSRF guard in
 * {@link WebhookUrlValidator} checks each answer; production uses the system resolver
 * ({@link #SYSTEM}), and tests replace this bean to control what a name resolves to.
 */
@FunctionalInterface
public interface HostResolver {

    HostResolver SYSTEM = InetAddress::getAllByName;

    InetAddress[] resolve(String host) throws UnknownHostException;
}
