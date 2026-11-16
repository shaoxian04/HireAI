// NetworkntSchemaValidatorTest.java
package com.hireai.infrastructure.adjudication;

import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkntSchemaValidatorTest {

    private final NetworkntSchemaValidator validator = new NetworkntSchemaValidator();

    @Test
    void invalidJsonIsReported() {
        JsonCheckResult r = validator.check("{not json", null);
        assertThat(r.validJson()).isFalse();
    }

    @Test
    void invalidJsonIsReportedInPlainWordsWithWhereNotTheParsersMessage() {
        JsonCheckResult r = validator.check("Extracted fields:\n{\"total\": 1}", null);

        assertThat(r.detail()).isEqualTo("the result isn't valid JSON (line 1, column 10)");
        assertThat(r.detail()).doesNotContain("Unrecognized").doesNotContain("StreamReadFeature")
                .doesNotContain("Source");
    }

    @Test
    void aSchemaMismatchListsThePlainMessagesWithoutTheValidatorsBrackets() {
        String schema = "{\"type\":\"object\",\"required\":[\"title\"]}";
        assertThat(validator.check("{\"x\":1}", schema).detail()).doesNotStartWith("[").contains("title");
    }

    @Test
    void anEmptyOrBlankPayloadIsNotJson() {
        for (String empty : new String[]{"", "   ", "\n", null}) {
            JsonCheckResult r = validator.check(empty, null);
            assertThat(r.validJson()).as("[%s]", empty).isFalse();
            assertThat(r.detail()).contains("empty");
        }
    }

    @Test
    void validJsonNoSchemaIsApplicableFalse() {
        JsonCheckResult r = validator.check("{\"a\":1}", null);
        assertThat(r.validJson()).isTrue();
        assertThat(r.schemaApplicable()).isFalse();
    }

    @Test
    void freeProseSchemaIsNotApplicable() {
        JsonCheckResult r = validator.check("{\"a\":1}", "must be a summary under 200 words");
        assertThat(r.validJson()).isTrue();
        assertThat(r.schemaApplicable()).isFalse();
    }

    @Test
    void aJsonSchemaObjectHasNoProblem() {
        assertThat(validator.problemWith("{}")).isEmpty();
        assertThat(validator.problemWith(
                "{\"type\":\"object\",\"required\":[\"total\"],\"properties\":{\"total\":{\"type\":\"number\"}}}"))
                .isEmpty();
    }

    @Test
    void proseIsNotJson() {
        assertThat(validator.problemWith("must be a summary under 200 words")).get().asString()
                .contains("not JSON");
    }

    @Test
    void jsonThatIsNotAnObjectIsNotASchemaObject() {
        assertThat(validator.problemWith("[1,2]")).get().asString().contains("not a JSON object");
        assertThat(validator.problemWith("true")).get().asString().contains("not a JSON object");
    }

    @Test
    void anObjectBreakingTheMetaSchemaIsNotAValidSchema() {
        assertThat(validator.problemWith("{\"type\":\"objekt\"}")).isPresent();
        assertThat(validator.problemWith("{\"required\":\"total\"}")).isPresent();
        assertThat(validator.problemWith("{\"minLength\":-1}")).isPresent();
    }

    // ---- a client schema never makes the backend fetch anything ----

    /** A local listener that records whether anything connected to it. */
    private static final class Listener implements AutoCloseable {
        final ServerSocket socket;
        final AtomicBoolean connected = new AtomicBoolean();
        final Thread acceptor;

        Listener() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            acceptor = new Thread(() -> {
                try (Socket ignored = socket.accept()) {
                    connected.set(true);
                } catch (IOException closed) {
                    // closed by the test: nothing connected
                }
            });
            acceptor.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + socket.getLocalPort() + path;
        }

        @Override
        public void close() throws Exception {
            Thread.sleep(300); // give a stray connection time to land
            socket.close();
            acceptor.join(2000);
        }
    }

    @Test
    void aRemoteRefIsRefusedWithoutConnecting() throws Exception {
        Listener listener = new Listener();
        String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"" + listener.url("/x") + "\"}}}";
        try (listener) {
            assertThat(validator.problemWith(schema)).get().asString().contains("only local references");
            assertThat(validator.check("{\"a\":1}", schema).schemaApplicable()).isFalse();
        }
        assertThat(listener.connected).isFalse();
    }

    @Test
    void theFactoryItselfRefusesToFetchARemoteRefEvenPastTheScan() throws Exception {
        Listener listener = new Listener();
        try (listener) {
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree("{\"$ref\":\"" + listener.url("/x") + "\"}");
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    validator.factory().getSchema(node).validate(
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree("{}")))
                    .hasStackTraceContaining("Refusing to load schema document");
        }
        assertThat(listener.connected).isFalse();
    }

    @Test
    void aFileRefIsRefused(@TempDir Path dir) throws Exception {
        Path other = dir.resolve("other.json");
        Files.writeString(other, "{\"type\":\"string\"}");
        String schema = "{\"$ref\":\"" + other.toUri() + "\"}";

        assertThat(validator.problemWith(schema)).get().asString().contains("only local references");
        assertThat(validator.problemWith("{\"$ref\":\"file:///etc/passwd\"}")).isPresent();
    }

    @Test
    void anUnsupportedOrRemoteSchemaDraftIsRefusedWithoutConnecting() throws Exception {
        Listener listener = new Listener();
        try (listener) {
            assertThat(validator.problemWith("{\"$schema\":\"" + listener.url("/meta") + "\"}"))
                    .get().asString().contains("only draft supported");
            assertThat(validator.problemWith("{\"$schema\":\"http://json-schema.org/draft-07/schema#\"}"))
                    .isPresent();
        }
        assertThat(listener.connected).isFalse();
    }

    @Test
    void aSchemaThatRebasesItselfWithIdIsRefused() throws Exception {
        Listener listener = new Listener();
        try (listener) {
            assertThat(validator.problemWith("{\"$id\":\"" + listener.url("/base") + "\",\"$ref\":\"#/$defs/a\","
                    + "\"$defs\":{\"a\":{\"type\":\"object\"}}}")).isPresent();
        }
        assertThat(listener.connected).isFalse();
    }

    @Test
    void localRefsAndTheSupportedDraftAreAllowed() {
        String schema = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
                + "\"properties\":{\"total\":{\"$ref\":\"#/$defs/money\"}},\"$defs\":{\"money\":{\"type\":\"number\"}}}";

        assertThat(validator.problemWith(schema)).isEmpty();
        assertThat(validator.check("{\"total\":5}", schema).schemaMatches()).isTrue();
        assertThat(validator.check("{\"total\":\"five\"}", schema).schemaMatches()).isFalse();
    }

    @Test
    void jsonSchemaMatchAndMismatch() {
        String schema = "{\"type\":\"object\",\"required\":[\"title\"],\"properties\":{\"title\":{\"type\":\"string\"}}}";
        assertThat(validator.check("{\"title\":\"hi\"}", schema).schemaMatches()).isTrue();
        JsonCheckResult miss = validator.check("{\"x\":1}", schema);
        assertThat(miss.schemaApplicable()).isTrue();
        assertThat(miss.schemaMatches()).isFalse();
    }
}
