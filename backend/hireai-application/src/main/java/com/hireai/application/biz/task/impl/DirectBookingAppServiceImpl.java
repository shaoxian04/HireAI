package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewPolicy;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewQuote;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.shared.model.Money;
import com.hireai.domain.biz.acceptance.coverage.info.ContractTerms;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.template.info.ContractResolution;
import com.hireai.domain.biz.acceptance.coverage.info.Coverage;
import com.hireai.domain.biz.acceptance.coverage.service.OfferCoverageDomainService;

import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.storefront.repository.StorefrontRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.ClientDeadline;
import com.hireai.domain.biz.task.model.ExecutionGrace;
import com.hireai.utility.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Direct booking orchestration. Validates that the target agent is ACTIVE and listed
 * (both failures surface as NOT_FOUND — existence is not leaked for unlisted agents,
 * per spec §6). Checks budget >= agent price. Adopts the agent's declared output_spec
 * as the task's binding contract (Hard Invariant #4) and its first capability category
 * for stats/labels. Delegates atomic submit + escrow freeze + pinned dispatch to the
 * write service (Hard Invariants #1 and #6).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DirectBookingAppServiceImpl implements DirectBookingAppService {

    private final AgentRepository agentRepository;
    private final StorefrontRepository agentProfileRepository;
    private final TaskWriteAppService taskWriteAppService;
    private final TemplateReadAppService templateReadAppService;
    private final OfferCoverageDomainService offerCoverageDomainService;
    /** The app's Clock bean: a booking's deadline is counted from the booking. */
    private final Clock clock;
    /** Added to the Agent's max execution time, as routing stamps it (ExecutionConfig). */
    private final ExecutionGrace executionGrace;
    /** Refuses a booking whose adopted schema could not be checked (spec D8). */
    private final ValidationDomainService validationDomainService;
    /** Prices AI Review and decides whether a booking may add it (spec S10). */
    private final AiReviewPolicy aiReviewPolicy;

    @Override
    public UUID book(DirectBookingInfo info) {
        AgentModel agent = agentRepository.findById(info.agentId())
                .orElseThrow(this::notFound);

        // Bookable = ACTIVE (agent state) + listed (storefront state). Each half is its owning
        // aggregate's own query; composing them across the two aggregates is app orchestration.
        // Both failures surface as NOT_FOUND so unlisted agents are indistinguishable from absent
        // ones (no existence leak — spec §6).
        if (!agent.isActive() || !isListed(agent.id())) {
            throw notFound();
        }

        // Pricing rule (budget >= price) is owned by the agent version.
        agent.currentVersion().assertAffordable(info.budget());
        if (info.deadlineSeconds() != null) {
            // A booking never substitutes another Agent: one that can't finish in time is a 400 now.
            Instant now = clock.instant();
            ClientDeadline.after(now, info.deadlineSeconds())
                    .requireAdmits(agent.currentVersion().maxExecutionSeconds(), now, executionGrace);
        }

        // An Agent registered before D8 may still carry a schema that is not a valid JSON Schema.
        // The booking would freeze it as the Contract; say whose schema it is, rather than the
        // request-field message the submit check gives.
        try {
            validationDomainService.requireCheckable(agent.currentVersion().outputSpec().toContract());
        } catch (DomainException e) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "This Agent's output schema is not a "
                    + "valid JSON Schema, so its results could not be checked and it can't be booked. "
                    + "Its Builder needs to fix the schema.");
        }

        ContractTerms terms = coveredTerms(agent, info.contract());
        Contract frozen = terms.contract();
        if (info.wantsAiReview()) {
            if (!info.items().isEmpty()) {
                throw new DomainException(ResultCode.VALIDATION_ERROR,
                        "AI Review is not offered on a Task with several Items");
            }
            // Priced from the Item's type and the criteria, offered only within the gate and when the
            // Offer accepts it (D37), then frozen with the Contract: never switched on mid-task (D5).
            frozen = frozen.withAiReview(aiReviewPolicy.termsFor(terms.template().outputShape(),
                    info.aiReviewCriteria(), info.budget(), agent.currentVersion().offer().acceptsAiReview()));
        }

        // Adopt the agent's declared output_spec as the task's binding contract (Invariant #4).
        // The task category (stats/labels, rebook) is the client's choice when given, which the
        // agent must serve; otherwise the agent's first capability category.
        TaskSubmitInfo submitInfo = new TaskSubmitInfo(
                info.clientId(), info.title(), info.description(), info.budget(),
                agent.currentVersion().outputSpec(), agent.currentVersion().taskCategoryFor(info.category()),
                info.deadlineSeconds(), frozen, info.items());

        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo, agent.currentVersionId());
        log.info("Task {} direct-booked to agent {} (version {})",
                taskId, agent.id(), agent.currentVersionId());
        return taskId;
    }

    @Override
    public AiReviewQuote quoteAiReview(UUID agentId, Contract contract, Money budget, int criteria) {
        AgentModel agent = agentRepository.findById(agentId).orElseThrow(this::notFound);
        if (!agent.isActive() || !isListed(agent.id())) {
            throw notFound();
        }
        ContractTerms terms = coveredTerms(agent, contract);
        return aiReviewPolicy.quote(terms.template().outputShape(), criteria, budget,
                agent.currentVersion().offer().acceptsAiReview());
    }

    /**
     * The Contract the booking freezes: the Client's own terms (#91), else the Agent's own spec's
     * equivalent. New Contracts use the current Template version, or during a transition the previous
     * one when this Agent's Offer covers only that (spec D36); either way the Agent's Offer must cover
     * it, or the Agent would be held to terms it never offered.
     */
    private ContractTerms coveredTerms(AgentModel agent, Contract contract) {
        Contract requested = contract != null ? contract : agent.currentVersion().outputSpec().toContract();
        Offer offer = agent.currentVersion().offer();
        ContractResolution resolution = templateReadAppService.resolveNew(requested, t ->
                offerCoverageDomainService.coverage(t.template(), t.contract(), offer).covered());
        ContractTerms terms = resolution.terms();
        Coverage coverage = offerCoverageDomainService.coverage(terms.template(), terms.contract(), offer);
        if (!coverage.covered()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, coverage.templateOffered()
                    ? "This Agent's Offer does not cover the Contract's " + String.join(", ",
                            coverage.blockingParameters())
                    : "This Agent does not offer Template " + terms.contract().template());
        }
        return terms;
    }

    private boolean isListed(UUID agentId) {
        return agentProfileRepository.findByAgentId(agentId)
                .map(StorefrontModel::listed)
                .orElse(false);
    }

    private DomainException notFound() {
        return new DomainException(ResultCode.NOT_FOUND, "Agent not found");
    }
}
