package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.application.biz.acceptance.engagement.EngagementAppService;
import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService;
import com.hireai.domain.biz.acceptance.engagement.model.EngagementModel;
import com.hireai.domain.biz.acceptance.source.model.SourceSnapshotModel;
import com.hireai.domain.biz.task.item.info.ItemInput;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
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
            // The Contract the booking freezes: the Client's own terms (#91), else the Agent's own spec's
            // equivalent. New Contracts use the current Template version, or during a transition the
            // previous one when this Agent's Offer covers only that (spec D36); either way the Agent's
            // Offer must cover it, or the Agent would be held to terms it never offered.
            Contract requested = info.contract() != null ? info.contract()
                    : agent.currentVersion().outputSpec().toContract();
            Offer offer = agent.currentVersion().offer();
            ContractResolution resolution = templateReadAppService.resolveNew(requested, t ->
                    offerCoverageDomainService.coverage(t.template(), t.contract(), offer).covered());
            terms = resolution.terms();
            Coverage coverage = offerCoverageDomainService.coverage(terms.template(), terms.contract(), offer);
            if (!coverage.covered()) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, coverage.templateOffered()
                        ? "This Agent's Offer does not cover the Contract's " + String.join(", ",
                                coverage.blockingParameters())
                        : "This Agent does not offer Template " + terms.contract().template());
            }
        }

        // Adopt the agent's declared output_spec as the task's binding contract (Invariant #4).
        // The task category (stats/labels, rebook) is the client's choice when given, which the
        // agent must serve; otherwise the agent's first capability category.
        TaskSubmitInfo submitInfo = new TaskSubmitInfo(
                info.clientId(), info.title(), info.description(), info.budget(),
                agent.currentVersion().outputSpec(), agent.currentVersion().taskCategoryFor(info.category()),
                info.deadlineSeconds(), terms.contract(), info.items());

        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo, agent.currentVersionId());
        log.info("Task {} direct-booked to agent {} (version {})",
                taskId, agent.id(), agent.currentVersionId());
        return taskId;
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
