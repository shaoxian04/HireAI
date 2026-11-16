package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.application.biz.acceptance.engagement.EngagementAppService;
import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService;
import com.hireai.domain.biz.acceptance.engagement.model.EngagementModel;
import com.hireai.domain.biz.acceptance.source.model.SourceSnapshotModel;
import com.hireai.domain.biz.task.item.info.ItemInput;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewPolicy;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewQuote;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.shared.model.Money;
import com.hireai.domain.biz.acceptance.coverage.info.ContractTerms;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.task.sampling.enums.InspectionLevel;
import com.hireai.domain.biz.task.sampling.model.InspectionModel;
import com.hireai.domain.biz.task.sampling.repository.InspectionRepository;
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
import java.util.List;
import java.util.Map;
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
    private final EngagementAppService engagementAppService;
    private final SourceSnapshotAppService sourceSnapshotAppService;
    /** Prices AI Review and decides whether a booking may add it (spec S10). */
    private final AiReviewPolicy aiReviewPolicy;
    /** Records a multi-Item booking's chosen inspection level (spec S8) in the booking transaction. */
    private final InspectionRepository inspectionRepository;

    @Override
    public UUID book(DirectBookingInfo booked) {
        DirectBookingInfo info = booked;
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

        // Under an Engagement (ADR 0010, D44) the Client chose this Agent and consented to send it its
        // documents; only then may an Item's input be a SourceSnapshot.
        EngagementModel engagement = null;
        if (info.engagementId() != null) {
            engagement = engagementAppService.getForClient(info.engagementId(), info.clientId());
            engagement.requireActive();
            engagement.requireAgent(agent.id());
            if (info.contract() == null && engagement.agentAccepted(engagement.templateVariant())) {
                // The Engagement's accepted Template variant is its default Contract.
                info = info.withContract(engagement.templateVariant());
            }
        } else if (info.hasSources()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "items: documents can be sent only under an "
                    + "Engagement with this Agent; pass its engagementId");
        }
        if (info.hasSources()) {
            info = info.withItems(attachSources(engagement, info.items()));
        }

        ContractTerms terms;
        if (info.contract() != null && engagement != null && engagement.agentAccepted(info.contract())) {
            // The Agent accepted exactly this Contract (Template version included) as the Engagement's
            // Template variant (D1): its explicit acceptance stands in for Offer coverage, and the version is
            // the one it accepted, never re-resolved. Its bounds are still checked.
            templateReadAppService.requireValid(info.contract());
            terms = templateReadAppService.termsFor(info.contract());
        } else {
            terms = coveredTerms(agent, info.contract());
        }
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

        // A multi-Item Task is inspected by sampling (spec S8, D21): the buyer may choose a stricter level
        // than the default, up to what this Agent's Offer accepts. Checked before anything is frozen.
        InspectionLevel level = info.items().isEmpty() ? null
                : InspectionLevel.chosen(info.inspectionLevel(), agent.currentVersion().offer().inspectionCeiling());
        if (info.items().isEmpty() && info.inspectionLevel() != null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "inspectionLevel: only a Task with Items is inspected by sampling");
        }
        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo, agent.currentVersionId());
        if (level != null) {
            inspectionRepository.save(InspectionModel.open(taskId, level));
        }
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

    /**
     * Locks the Items' documents (so retention cannot retire one under this booking) and checks each was
     * uploaded under the Engagement and still holds its content. A document Item with no text input takes
     * the document's file name as its input.
     */
    private List<ItemInput> attachSources(EngagementModel engagement, List<ItemInput> items) {
        List<UUID> ids = items.stream().filter(ItemInput::hasSource).map(ItemInput::sourceSnapshotId)
                .distinct().toList();
        Map<UUID, SourceSnapshotModel> sources = sourceSnapshotAppService.attachForBooking(engagement, ids);
        return items.stream()
                .map(item -> item.hasSource() && (item.input() == null || item.input().isBlank())
                        ? new ItemInput(sources.get(item.sourceSnapshotId()).filename(), item.sourceSnapshotId())
                        : item)
                .toList();
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
