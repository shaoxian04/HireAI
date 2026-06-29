/**
 * Adjudication subdomain (裁决域, Core) — RESERVED, no implementation yet.
 *
 * <p>This package reserves the Adjudication subdomain's place in the capability map established by
 * the backend capability re-division (spec
 * {@code docs/superpowers/specs/2026-06-29-backend-capability-redivision-design.md}). It is the home
 * for Module 4, which is deferred (spec §7) and will land as its own brainstorm → spec → plan cycle.
 *
 * <p>Planned contents:
 * <ul>
 *   <li><b>Validation gate</b> — {@code ValidationReport} (root) + {@code CheckResult} (VO): the
 *       automated check of a result against the task's frozen {@code output_spec} that drives
 *       {@code RESULT_RECEIVED → PENDING_REVIEW} (pass) or {@code → SPEC_VIOLATION} (fail) before any
 *       client sees a result (Hard Invariant #4).</li>
 *   <li><b>Tiered dispute resolution</b> — {@code Dispute} (root) + {@code Ruling} (VO): the
 *       reason-gate ({@code D_CHANGED_MIND} deterministic vs {@code A/B/C} arbitrate), the tier-1 LLM
 *       Arbitrator (external Python service), and the tier-2 Administrator backstop.</li>
 *   <li>The deterministic mapping from a ruling category to a settlement (Hard Invariant #3) — the
 *       LLM proposes a ruling; the domain disposes of the money.</li>
 * </ul>
 *
 * <p>No types live here yet. Do not add Adjudication logic outside its own dedicated spec/plan.
 */
package com.hireai.domain.biz.adjudication;
