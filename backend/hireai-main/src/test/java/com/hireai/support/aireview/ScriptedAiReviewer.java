package com.hireai.support.aireview;

import com.hireai.application.port.aireview.AiReviewSubject;
import com.hireai.application.port.aireview.AiReviewerPort;
import com.hireai.application.port.aireview.AiReviewerUnavailableException;
import com.hireai.domain.biz.acceptance.aireview.model.CriterionVerdict;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The harness's scripted AI reviewer (spec S10 testing decisions): no test ever calls a real model.
 * It answers from tags in each criterion's own text, so a test scripts the verdict where it books:
 * <ul>
 *   <li>{@code #fail} in a criterion → that criterion fails, every other one passes;</li>
 *   <li>{@code #error} → a transient failure (timeout/5xx) on every attempt;</li>
 *   <li>{@code #timeout-once} → a transient failure on the first attempt only;</li>
 *   <li>{@code #refuse} → a refusal (an unusable answer) on every attempt;</li>
 *   <li>{@code #refuse-once} → a refusal on the first attempt only;</li>
 *   <li>{@code #partial} in a criterion → the answer leaves that criterion unjudged (every attempt).</li>
 * </ul>
 * Attempts are counted per Task title. Every subject it was asked to judge is recorded, so a test can
 * see what reached the reviewer. One JVM-wide instance, shared by every Spring context.
 */
public final class ScriptedAiReviewer implements AiReviewerPort {

    public static final String MODEL = "scripted-reviewer";
    private static final ScriptedAiReviewer SHARED = new ScriptedAiReviewer();

    private final List<AiReviewSubject> subjects = new CopyOnWriteArrayList<>();

    public static ScriptedAiReviewer shared() {
        return SHARED;
    }

    @Override
    public Answer review(AiReviewSubject subject) {
        subjects.add(subject);
        boolean first = subjectsTitled(subject.title()).size() == 1;
        if (tagged(subject, "#error") || (first && tagged(subject, "#timeout-once"))) {
            throw new AiReviewerUnavailableException("scripted timeout");
        }
        if (tagged(subject, "#refuse ") || subject.criteria().stream().anyMatch(c -> c.endsWith("#refuse"))
                || (first && tagged(subject, "#refuse-once"))) {
            throw AiReviewerUnavailableException.unusableAnswer("The AI reviewer refused to judge the deliverable");
        }
        return new Answer(subject.criteria().stream()
                .filter(c -> !c.contains("#partial")) // a verdict that leaves a criterion unjudged
                .map(c -> new CriterionVerdict(c, !c.contains("#fail"),
                        c.contains("#fail") ? "The deliverable does not show this." : "Seen in the deliverable."))
                .toList(), MODEL);
    }

    private static boolean tagged(AiReviewSubject subject, String tag) {
        return subject.criteria().stream().anyMatch(c -> c.contains(tag));
    }

    /** The subjects reviewed for the Task titled {@code title}, oldest first. */
    public List<AiReviewSubject> subjectsTitled(String title) {
        return subjects.stream().filter(s -> s.title().equals(title)).toList();
    }
}
