package com.hireai.domain.biz.acceptance.template.model;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.coverage.info.ContractTerms;
import com.hireai.domain.biz.acceptance.template.enums.TemplateStatus;
import com.hireai.domain.biz.acceptance.template.info.ContractResolution;
import com.hireai.domain.biz.acceptance.template.info.TemplateLifecycle;
import com.hireai.domain.biz.acceptance.template.info.VersionFallback;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Every published version of one Template key, and the transition between them (spec D36).
 *
 * <ul>
 *   <li>The <b>current</b> version is the newest ACTIVE one. New Contracts use it.</li>
 *   <li>The <b>previous</b> version is the newest ACTIVE one below it. It stays live for the transition
 *       period, counted from the current version's publication: a new Contract falls back to it when no
 *       Offer covers the current version, and the Client is told ({@link VersionFallback}).</li>
 *   <li>After the transition, and for any older or RETIRED version, no new Contracts.</li>
 * </ul>
 *
 * A frozen Contract is unaffected: it is always judged under its own version. An Offer covers only the
 * versions it names (coverage), so a new version never binds a Builder to terms it has not seen.
 */
public final class TemplateVersions {

    private final List<TemplateModel> newestFirst;
    private final Duration transition;

    private TemplateVersions(List<TemplateModel> versions, Duration transition) {
        if (transition == null || transition.isNegative()) {
            throw new DomainException(ResultCode.INTERNAL_ERROR, "The Template transition must not be negative");
        }
        if (versions.isEmpty() || versions.stream().map(v -> v.ref().key()).distinct().count() != 1) {
            throw new DomainException(ResultCode.INTERNAL_ERROR, "Template versions must share one key");
        }
        this.newestFirst = versions.stream()
                .sorted(Comparator.comparingInt((TemplateModel v) -> v.ref().version()).reversed())
                .toList();
        this.transition = transition;
    }

    /** The versions of one key (any order) with the configured transition period. */
    public static TemplateVersions of(List<TemplateModel> versions, Duration transition) {
        return new TemplateVersions(versions, transition);
    }

    /** The newest ACTIVE version; empty when the whole line is retired. */
    public Optional<TemplateModel> current() {
        return newestFirst.stream().filter(v -> v.status() == TemplateStatus.ACTIVE).findFirst();
    }

    /** The previous version, while its transition has not ended at {@code now}. */
    private Optional<TemplateModel> previousInTransition(Instant now) {
        Optional<TemplateModel> current = current();
        if (current.isEmpty() || current.get().publishedAt() == null
                || !now.isBefore(transitionEnd(current.get()))) {
            return Optional.empty();
        }
        int currentVersion = current.get().ref().version();
        return newestFirst.stream()
                .filter(v -> v.status() == TemplateStatus.ACTIVE && v.ref().version() < currentVersion)
                .findFirst();
    }

    private Instant transitionEnd(TemplateModel current) {
        return current.publishedAt().plus(transition);
    }

    /** The versions taking new Contracts at {@code now}, in preference order (current first). */
    private List<TemplateModel> live(Instant now) {
        List<TemplateModel> live = new ArrayList<>(2);
        current().ifPresent(live::add);
        previousInTransition(now).ifPresent(live::add);
        return live;
    }

    public TemplateLifecycle lifecycleOf(TemplateRef ref, Instant now) {
        if (current().map(c -> c.ref().equals(ref)).orElse(false)) {
            return new TemplateLifecycle(true, true, null);
        }
        Optional<TemplateModel> previous = previousInTransition(now).filter(p -> p.ref().equals(ref));
        return previous.isPresent()
                ? new TemplateLifecycle(false, true, transitionEnd(current().orElseThrow()))
                : new TemplateLifecycle(false, false, null);
    }

    /** VALIDATION_ERROR unless {@code ref} takes new Contracts (or Offer terms) at {@code now}. */
    public void requireTakesNewContracts(TemplateRef ref, Instant now) {
        if (!lifecycleOf(ref, now).takesNewContracts()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Template " + ref + " takes no new Contracts"
                    + current().map(c -> "; use " + c.ref()).orElse("; its line is retired"));
        }
    }

    /**
     * Chooses the version a new Contract is made under. {@code requested} supplies the parameter values;
     * its version is only a hint, since new Contracts use the current one. Each live version where the
     * values are in bounds is tried in order (current, then the previous one in transition) and the first
     * that {@code covered} accepts wins. When none is covered the first in-bounds version is kept, so the
     * no-match explanation names it. VALIDATION_ERROR when the values fit no live version.
     *
     * <p>{@code covered} is asked only when there is a choice to make (two in-bounds live versions).
     */
    public ContractResolution resolve(Contract requested, Instant now, Predicate<ContractTerms> covered) {
        List<TemplateModel> live = live(now);
        if (live.isEmpty()) {
            requireTakesNewContracts(requested.template(), now);
        }
        List<ContractTerms> inBounds = new ArrayList<>(2);
        DomainException firstProblem = null;
        for (TemplateModel version : live) {
            Contract retargeted = new Contract(version.ref(), requested.params());
            try {
                version.requireValid(retargeted);
                inBounds.add(new ContractTerms(version, retargeted));
            } catch (DomainException e) {
                if (firstProblem == null) {
                    firstProblem = e;
                }
            }
        }
        if (inBounds.isEmpty()) {
            throw firstProblem;
        }
        ContractTerms chosen = inBounds.size() == 1 ? inBounds.get(0)
                : inBounds.stream().filter(covered).findFirst().orElse(inBounds.get(0));
        TemplateModel current = live.get(0);
        VersionFallback fallback = chosen.template() == current ? null
                : new VersionFallback(current.ref(), chosen.template().ref(), transitionEnd(current));
        return new ContractResolution(chosen, fallback);
    }
}
