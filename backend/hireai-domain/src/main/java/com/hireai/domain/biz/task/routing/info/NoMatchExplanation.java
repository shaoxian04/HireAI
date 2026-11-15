package com.hireai.domain.biz.task.routing.info;

import com.hireai.domain.biz.acceptance.template.model.TemplateRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Why nothing matched a task (spec D35), computed by {@code RoutingMatchDomainService.explainNoMatch}
 * over the Agents that serve the category within budget (and the deadline, applied by the query).
 *
 * <p>{@code exclusions}: each blocker with the number of those Agents it excluded. An Agent can be
 * excluded by several blockers and then counts under each. Ordered by count (most first), then
 * parameters before the relevance floor before the Template, then by name.
 *
 * <p>{@code suggestions}: for each Contract parameter, how many Agents would become eligible if the
 * Client left it unset, counting only Agents that parameter alone excluded. The platform never applies
 * one itself; the Client decides. Ordered by count (most first), then name.
 */
public record NoMatchExplanation(TemplateRef template, List<Exclusion> exclusions,
                                 List<RelaxSuggestion> suggestions) {

    public enum Blocker {
        /** A Contract parameter the Agent's Offer does not cover. */
        PARAMETER,
        /** The Agent's profile is below the embedding relevance floor for the task text. */
        RELEVANCE_FLOOR,
        /** The Agent's Offer does not name the Contract's Template version. */
        TEMPLATE
    }

    /** {@code parameter} is set only for {@link Blocker#PARAMETER}. */
    public record Exclusion(Blocker blocker, String parameter, int agents) {
    }

    public record RelaxSuggestion(String parameter, int agents) {
    }

    public NoMatchExplanation {
        exclusions = List.copyOf(exclusions);
        suggestions = List.copyOf(suggestions);
    }

    /** One plain sentence for a task's failure detail and logs. */
    public String summary() {
        if (exclusions.isEmpty()) {
            return "No Agent serves this category within the budget.";
        }
        List<String> parts = new ArrayList<>();
        for (Exclusion e : exclusions) {
            parts.add(switch (e.blocker()) {
                case PARAMETER -> "parameter " + e.parameter() + " excluded " + agents(e.agents());
                case RELEVANCE_FLOOR -> "the relevance floor excluded " + agents(e.agents());
                case TEMPLATE -> agents(e.agents()) + (e.agents() == 1 ? " does" : " do")
                        + " not offer Template " + (template == null ? "of the Contract" : template);
            });
        }
        return "No eligible Agent: " + String.join("; ", parts) + ".";
    }

    private static String agents(int n) {
        return n + (n == 1 ? " Agent" : " Agents");
    }
}
