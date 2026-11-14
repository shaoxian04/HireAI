package com.hireai.domain.biz.task.routing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The τ test set (#85, ADR 0006): task and Agent texts with vectors recorded ONCE from the real
 * embedding model (fixture {@code matching/tau-test-set.json}; the test itself never calls a
 * provider). It pins which (task, Agent) pairs pass the relevance floor at the configured τ, so a
 * change to τ or to the model shows up as a failing pin. Re-record the vectors when the model changes,
 * then re-decide τ from the printed similarities, never by editing the expectations to fit.
 *
 * <p>What the recording showed for text-embedding-3-small at τ = 0.25: every intended specialist match
 * scored 0.47-0.67 and every pair from a different bucket 0.10-0.24, so τ cleanly removes those. Pairs
 * from neighbouring Agents in the same bucket land 0.22-0.33, on both sides of τ (a general summariser
 * misses a lease at 0.23 while a medical summariser passes at 0.27): there the floor is noise and the
 * relevance weight does the ordering.
 */
class TauTestSetTest {

    /** Every pair expected to reach τ; all other pairs of the set are expected to fall below it. */
    private static final Set<String> PASSING = Set.of(
            "summarise-lease|legal-summariser",        // 0.50
            "summarise-lease|medical-summariser",      // 0.27 (same bucket, wrong domain)
            "summarise-nda|legal-summariser",          // 0.47
            "summarise-nda|medical-summariser",        // 0.28 (same bucket, wrong domain)
            "summarise-article|general-summariser",    // 0.49
            "summarise-article|legal-summariser",      // 0.30
            "summarise-article|medical-summariser",    // 0.32
            "summarise-discharge|medical-summariser",  // 0.55
            "summarise-discharge|general-summariser",  // 0.32
            "summarise-discharge|legal-summariser",    // 0.26
            "extract-invoice|invoice-extractor",       // 0.67
            "fix-python|code-reviewer");               // 0.61

    /** The intended specialist for each task: always passes and is always the closest Agent. */
    private static final Map<String, String> SPECIALIST = Map.of(
            "summarise-lease", "legal-summariser",
            "summarise-nda", "legal-summariser",
            "summarise-article", "general-summariser",
            "summarise-discharge", "medical-summariser",
            "extract-invoice", "invoice-extractor",
            "fix-python", "code-reviewer");

    private static JsonNode fixture;
    private static Map<String, float[]> tasks;
    private static Map<String, float[]> agents;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = TauTestSetTest.class.getResourceAsStream("/matching/tau-test-set.json")) {
            fixture = new ObjectMapper().readTree(in);
        }
        tasks = vectors(fixture.get("tasks"));
        agents = vectors(fixture.get("agents"));
    }

    @Test
    void theFixtureWasRecordedWithTheConfiguredModel() throws IOException {
        assertThat(fixture.get("model").asText()).isEqualTo(configured("model: \\$\\{EMBEDDING_MODEL:([^}]+)}"));
    }

    @Test
    void theTauUnderTestIsTheConfiguredDefault() throws IOException {
        assertThat(MatchingPolicy.defaults().relevanceFloor())
                .isEqualTo(Double.parseDouble(configured("relevance-floor: \\$\\{MATCHING_RELEVANCE_FLOOR:([^}]+)}")));
    }

    @Test
    void theFloorPassesExactlyThePinnedPairs() {
        MatchingPolicy policy = MatchingPolicy.defaults();
        Set<String> passing = new TreeSet<>();
        StringBuilder table = new StringBuilder();
        tasks.forEach((task, taskVector) -> agents.forEach((agent, agentVector) -> {
            double similarity = cosine(taskVector, agentVector);
            table.append(String.format("%-22s %-20s %.4f%n", task, agent, similarity));
            if (policy.passesRelevanceFloor(similarity)) {
                passing.add(task + "|" + agent);
            }
        }));
        assertThat(passing).as("pairs passing τ=%s%n%s", policy.relevanceFloor(), table)
                .containsExactlyInAnyOrderElementsOf(PASSING);
    }

    @Test
    void eachTasksSpecialistIsItsClosestAgent() {
        SPECIALIST.forEach((task, specialist) -> {
            String closest = agents.entrySet().stream()
                    .max((a, b) -> Double.compare(cosine(tasks.get(task), a.getValue()),
                            cosine(tasks.get(task), b.getValue())))
                    .orElseThrow().getKey();
            assertThat(closest).as(task).isEqualTo(specialist);
        });
    }

    private static Map<String, float[]> vectors(JsonNode group) {
        Map<String, float[]> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = group.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(e.getValue().get("vector").asText()))
                    .order(ByteOrder.LITTLE_ENDIAN);
            float[] v = new float[buf.remaining() / Float.BYTES];
            buf.asFloatBuffer().get(v);
            assertThat(v).as(e.getKey()).hasSize(1536);
            out.put(e.getKey(), v);
        }
        return out;
    }

    /** Cosine similarity, the quantity pgvector's {@code 1 - (a <=> b)} computes in the candidate query. */
    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String configured(String regex) throws IOException {
        try (InputStream in = TauTestSetTest.class.getResourceAsStream("/application.yml")) {
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(regex).matcher(yml);
            assertThat(m.find()).as("application.yml has " + regex).isTrue();
            return m.group(1).trim();
        }
    }
}
