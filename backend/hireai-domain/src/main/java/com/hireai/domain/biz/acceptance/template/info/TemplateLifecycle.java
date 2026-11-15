package com.hireai.domain.biz.acceptance.template.info;

import java.time.Instant;

/**
 * Where one Template version stands in its line (spec D36). {@code current}: new Contracts use it.
 * {@code takesNewContracts}: it is current, or it is the previous version and its transition has not
 * ended. {@code takesNewContractsUntil}: the end of that transition (null unless in transition).
 */
public record TemplateLifecycle(boolean current, boolean takesNewContracts, Instant takesNewContractsUntil) {
}
