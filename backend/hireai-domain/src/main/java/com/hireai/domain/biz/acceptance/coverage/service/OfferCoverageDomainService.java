package com.hireai.domain.biz.acceptance.coverage.service;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.coverage.info.Coverage;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;

/**
 * Offer coverage (spec D34, ADR 0009). Framework-free; wired in DomainServiceConfig. An Offer covers a
 * Contract when it has a term for the Contract's exact Template version (D36) and every parameter the
 * Contract sets passes that parameter's comparison against the term's range:
 * <ul>
 *   <li>{@code max}: value {@code <=} the bound; {@code min}: value {@code >=} the bound;</li>
 *   <li>{@code subset}: every value in the range's set (or the range is any value);</li>
 *   <li>{@code enum}: the value in the range's set (or the range is any value);</li>
 *   <li>{@code flag}: the flag is false, or the range accepts true.</li>
 * </ul>
 * A parameter the Contract leaves unset is always covered. A parameter the term does not name covers
 * only an unset value (or a false flag, which asks nothing): an Agent is never held to terms it did not
 * name. Price within budget is the matcher's own filter, not part of this check.
 */
public interface OfferCoverageDomainService {

    Coverage coverage(TemplateModel template, Contract contract, Offer offer);
}
