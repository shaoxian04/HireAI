package com.hireai.support.delivery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A throwaway TLS identity for {@code localhost}, made once per test JVM with the JDK's own
 * {@code keytool} (no key material is committed). {@link #serverKeyStore()} holds the key pair for
 * a local HTTPS server; {@link #trustStore()} holds only its certificate, for
 * {@code hireai.delivery.trust-store}.
 */
public final class TestTls {

    public static final String PASSWORD = "changeit";

    private static Path dir;

    private TestTls() {
    }

    public static synchronized Path serverKeyStore() {
        return generated().resolve("server.p12");
    }

    public static synchronized Path trustStore() {
        return generated().resolve("trust.p12");
    }

    private static Path generated() {
        if (dir != null) {
            return dir;
        }
        try {
            Path d = Files.createTempDirectory("hireai-test-tls");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            run(List.of(keytool, "-genkeypair", "-alias", "localhost", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "3650",
                    "-storetype", "PKCS12", "-keystore", d.resolve("server.p12").toString(),
                    "-storepass", PASSWORD, "-keypass", PASSWORD));
            run(List.of(keytool, "-exportcert", "-alias", "localhost", "-keystore", d.resolve("server.p12").toString(),
                    "-storepass", PASSWORD, "-file", d.resolve("server.cer").toString()));
            run(List.of(keytool, "-importcert", "-noprompt", "-alias", "localhost", "-file",
                    d.resolve("server.cer").toString(), "-storetype", "PKCS12",
                    "-keystore", d.resolve("trust.p12").toString(), "-storepass", PASSWORD));
            dir = d;
            return d;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool failed: " + new String(process.getInputStream().readAllBytes()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
