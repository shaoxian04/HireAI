package com.hireai.domain.biz.acceptance.offer.model;

import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An Agent version's standing declaration of the Template versions it works under and the
 * parameter range it accepts on each (ADR 0009). It covers only the versions it names (spec D36).
 * The Offer's price is the agent version's {@code Pricing}; it is not repeated here.
 *
 * <p>{@code acceptsAiReview} is the Builder's consent to AI Review (spec S10, D38): only then may a
 * Client add one to a booking, and on API/MCP its verdict settles the Task and is final.
 */
public record Offer(List<OfferTerm> terms, boolean acceptsAiReview) {

    public Offer {
        if (terms == null || terms.isEmpty() || terms.stream().anyMatch(t -> t == null)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "An Offer needs at least one Template");
        }
        Set<TemplateRef> seen = new HashSet<>();
        for (OfferTerm term : terms) {
            if (!seen.add(term.template())) {
                throw new DomainException(ResultCode.VALIDATION_ERROR,
                        "An Offer names " + term.template() + " more than once");
            }
        }
        terms = List.copyOf(terms);
    }

    public Offer(List<OfferTerm> terms) {
        this(terms, false);
    }

    /** This Offer with AI Review accepted or not. */
    public Offer withAiReview(boolean accepts) {
        return new Offer(terms, accepts);
    }

    public static Offer of(OfferTerm... terms) {
        return new Offer(List.of(terms));
    }
}
