package com.hireai.controller.biz.task;

import com.hireai.application.biz.adjudication.validation.ValidationReadAppService;
import com.hireai.application.biz.task.MatchPreviewAppService;
import com.hireai.application.biz.task.SubmitContext;
import com.hireai.application.biz.task.SubmitOrchestrationAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.reputation.ReviewAppService;
import com.hireai.application.biz.task.RejectionAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.biz.agent.dto.ReviewDTO;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.adjudication.ValidationReport2DTOConverter;
import com.hireai.controller.biz.adjudication.dto.ValidationReportDTO;
import com.hireai.controller.biz.task.converter.MatchPreview2DTOConverter;
import com.hireai.controller.biz.task.converter.TaskModel2DTOConverter;
import com.hireai.controller.biz.task.converter.TaskResult2DTOConverter;
import com.hireai.controller.biz.task.dto.CreateReviewRequest;
import com.hireai.controller.biz.task.dto.DirectBookRequest;
import com.hireai.controller.biz.task.dto.DispatchAttemptDTO;
import com.hireai.controller.biz.task.dto.MatchPreviewDTO;
import com.hireai.controller.biz.task.dto.MatchPreviewRequest;
import com.hireai.controller.biz.acceptance.converter.Acceptance2DTOConverter;
import com.hireai.controller.biz.acceptance.converter.ContractRequest2ModelConverter;
import com.hireai.controller.biz.acceptance.dto.ContractRequest;
import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.controller.biz.task.dto.RejectTaskRequest;
import com.hireai.controller.biz.task.dto.SubmitTaskRequest;
import com.hireai.controller.biz.task.dto.TaskDTO;
import com.hireai.controller.biz.task.dto.TaskResultDTO;
import com.hireai.controller.config.ApiKeyContext;
import com.hireai.controller.config.CurrentApiKeyProvider;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.acceptance.extraction.model.ExtractionRecord;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import org.springframework.http.ResponseEntity;
import com.hireai.domain.biz.task.repository.TaskQuery;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.hireai.domain.biz.task.item.info.ItemInput;
import com.hireai.domain.biz.task.item.model.TaskItems;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task HTTP surface. Thin: validate the request, resolve identity server-side, build the
 * domain carrier, call one app service, wrap the result. Identity comes from
 * {@link CurrentUserProvider} (the JWT principal once auth lands) — never from path or body.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController extends BaseController {

    private final SubmitOrchestrationAppService submitOrchestrationAppService;
    private final TaskReadAppService readAppService;
    private final CurrentUserProvider currentUser;
    private final CurrentApiKeyProvider currentApiKey;
    private final TaskReviewAppService reviewAppService;
    /** At most this many Agents can be excluded from one match preview. */
    static final int MAX_EXCLUDED_AGENTS = 20;

    private final MatchPreviewAppService matchPreviewAppService;
    private final ValidationReadAppService validationReadAppService;
    /** The client's star rating — distinct from reviewAppService, which is accept/reject. */
    private final ReviewAppService ratingAppService;
    private final RejectionAppService rejectionAppService;
    /** Checks the checks a structured Contract names (spec D15). */
    private final TemplateReadAppService templateReadAppService;

    public TaskController(SubmitOrchestrationAppService submitOrchestrationAppService,
                          TaskReadAppService readAppService,
                          CurrentUserProvider currentUser,
                          CurrentApiKeyProvider currentApiKey,
                          TaskReviewAppService reviewAppService,
                          MatchPreviewAppService matchPreviewAppService,
                          ValidationReadAppService validationReadAppService,
                          ReviewAppService ratingAppService,
                          RejectionAppService rejectionAppService,
                          TemplateReadAppService templateReadAppService) {
        this.submitOrchestrationAppService = submitOrchestrationAppService;
        this.readAppService = readAppService;
        this.currentUser = currentUser;
        this.currentApiKey = currentApiKey;
        this.reviewAppService = reviewAppService;
        this.matchPreviewAppService = matchPreviewAppService;
        this.validationReadAppService = validationReadAppService;
        this.ratingAppService = ratingAppService;
        this.rejectionAppService = rejectionAppService;
        this.templateReadAppService = templateReadAppService;
    }

    /**
     * Submits a routed task in one of two modes (spec S6, D28). Without {@code contract} (mode 1) the
     * description is extracted onto the output spec's Template and the result adopted: submitting is
     * acceptance, and the response's {@code extraction} says how each requirement was mapped. With a
     * {@code contract} (mode 2) nothing is extracted. Either way the response carries the frozen Contract.
     */
    @PostMapping
    public WebResult<TaskDTO> submit(@Valid @RequestBody SubmitTaskRequest request,
                                     @RequestHeader(value = "Idempotency-Key", required = false)
                                     String idempotencyKey) {
        UUID clientId = currentUser.currentUserId();
        Contract contract = contractOf(request.contract());
        SubmitTaskRequest.OutputSpecRequest specRequest = request.outputSpec();
        OutputSpec outputSpec = specRequest != null
                ? new OutputSpec(specRequest.format(), specRequest.schema(), specRequest.acceptanceCriteria())
                // Mode 1 without an output spec extracts onto text_basic; mode 2 takes the Contract's shape.
                : contract == null ? new OutputSpec(OutputFormat.TEXT, null, null) : null;
        TaskSubmitInfo info = new TaskSubmitInfo(
                clientId,
                request.title(),
                request.description(),
                Money.of(request.budget()),
                outputSpec,
                request.category(),
                request.deadlineSeconds(),
                contract,
                // Text Items only: documents travel only under an Engagement (ADR 0010).
                request.items() == null ? null : request.items().stream().map(ItemInput::text).toList());
        UUID taskId = submitOrchestrationAppService.submitRouted(
                submitContext(clientId, idempotencyKey), info);
        TaskModel task = readAppService.getForClient(taskId, clientId);
        return ok(withTemplateFallback(task).withExtraction(
                Acceptance2DTOConverter.toDTO(readAppService.findContractExtraction(task).orElse(null))));
    }

    /** The request's structured Contract (null when none), after checking the checks it names. */
    private Contract contractOf(ContractRequest request) {
        Contract contract = ContractRequest2ModelConverter.toModel(request);
        if (contract != null) {
            templateReadAppService.requireNamedChecks(contract, request.checks());
        }
        return contract;
    }

    @PostMapping("/direct")
    public WebResult<TaskDTO> bookDirect(@Valid @RequestBody DirectBookRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false)
                                         String idempotencyKey) {
        UUID clientId = currentUser.currentUserId();
        SubmitContext ctx = submitContext(clientId, idempotencyKey);
        DirectBookingInfo info = new DirectBookingInfo(clientId, request.title(), request.description(),
                Money.of(request.budget()), request.agentId(),
                request.category() == null || request.category().isBlank() ? null : request.category(),
                request.deadlineSeconds(), contractOf(request.contract()),
                request.itemInputs(), request.engagementId(),
                request.aiReview() == null ? List.of() : request.aiReview().criteria(),
                request.inspectionLevel());
        ExtractionRecord extraction = ContractRequest2ModelConverter.toModel(request.extraction());
        UUID taskId = extraction == null
                ? submitOrchestrationAppService.submitDirect(ctx, info)
                : submitOrchestrationAppService.submitDirect(ctx, info, extraction);
        TaskModel task = readAppService.getForClient(taskId, clientId);
        return ok(withTemplateFallback(task).withExtraction(
                Acceptance2DTOConverter.toDTO(readAppService.findContractExtraction(task).orElse(null))));
    }

    /** A submit/booking response: the task, and whether its Contract fell back a version (spec D36). */
    private TaskDTO withTemplateFallback(TaskModel task) {
        return TaskModel2DTOConverter.toDTO(task).withTemplateFallback(
                Acceptance2DTOConverter.toDTO(readAppService.findTemplateFallback(task).orElse(null)));
    }

    /**
     * Assembles the submit context from the JWT user, the optional Idempotency-Key header, and — if
     * the request was API-key authenticated — the key id + spend caps. The app layer never touches the
     * SecurityContext (Hard Invariant #5).
     */
    private SubmitContext submitContext(UUID ownerId, String idempotencyKey) {
        Optional<ApiKeyContext> apiKey = currentApiKey.current();
        return new SubmitContext(ownerId, idempotencyKey,
                apiKey.map(ApiKeyContext::keyId).orElse(null),
                apiKey.map(ApiKeyContext::spendCap).orElse(null),
                apiKey.map(ApiKeyContext::dailySpendCap).orElse(null));
    }

    @GetMapping("/{id}")
    public WebResult<TaskDTO> getById(@PathVariable("id") UUID id) {
        UUID clientId = currentUser.currentUserId();
        TaskModel task = readAppService.getForClient(id, clientId);
        UUID agentId = readAppService.findBookedAgentId(task).orElse(null);
        return ok(TaskModel2DTOConverter.toDTO(task, agentId,
                readAppService.findReviewWindow(task).orElse(null),
                rejectionAppService.findLatest(task.id()).orElse(null),
                readAppService.findFailureDetail(task).orElse(null), readAppService.findItems(task), true,
                readAppService.findSettlementRecord(task))
                .withTemplateFallback(Acceptance2DTOConverter.toDTO(
                        readAppService.findTemplateFallback(task).orElse(null)))
                .withReviewReason(readAppService.findReviewReason(task).orElse(null))
                .withExtraction(Acceptance2DTOConverter.toDTO(
                        readAppService.findContractExtraction(task).orElse(null))));
    }

    @GetMapping("/{taskId}/result")
    public WebResult<TaskResultDTO> getResult(@PathVariable("taskId") UUID taskId) {
        UUID clientId = currentUser.currentUserId();
        TaskResultModel result = readAppService.getResult(taskId, clientId);
        TaskResultDTO dto = TaskResult2DTOConverter.toDTO(result,
                readAppService.findDeliverySnapshot(result).orElse(null));
        return ok(dto);
    }

    /**
     * The frozen file of a FILE result (D24), owner-only: the DeliverySnapshot's bytes, never the
     * Agent's link. The bytes are Agent-supplied, so they are always sent as an attachment with
     * {@code nosniff} and a sandbox CSP, and as {@code application/octet-stream} unless the platform
     * recognised them as one of the file_basic types ({@link DeliverableResponse}).
     */
    @GetMapping("/{taskId}/deliverable")
    public ResponseEntity<byte[]> getDeliverable(@PathVariable("taskId") UUID taskId) {
        UUID clientId = currentUser.currentUserId();
        return DeliverableResponse.of(taskId, readAppService.getDeliverable(taskId, clientId));
    }

    @GetMapping("/{id}/validation")
    public WebResult<ValidationReportDTO> getValidation(@PathVariable("id") UUID id) {
        UUID clientId = currentUser.currentUserId();
        readAppService.getForClient(id, clientId); // ownership guard (Hard Invariant #5)
        List<ValidationReportModel> history = validationReadAppService.historyForTask(id);
        if (history.isEmpty()) {
            throw new DomainException(ResultCode.NOT_FOUND, "No validation report for task: " + id);
        }
        return ok(ValidationReport2DTOConverter.toDTO(history, validationReadAppService.maxReworks()));
    }

    /** The task's dispatch history (#75), owner-only: another client gets 404, not the rows. */
    @GetMapping("/{id}/attempts")
    public WebResult<List<DispatchAttemptDTO>> getAttempts(@PathVariable("id") UUID id) {
        UUID clientId = currentUser.currentUserId();
        return ok(readAppService.listDispatchAttempts(id, clientId).stream()
                .map(DispatchAttemptDTO::from)
                .toList());
    }

    @GetMapping
    public WebResult<List<TaskDTO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID clientId = currentUser.currentUserId();
        List<TaskModel> owned = readAppService.listForClient(clientId, new TaskQuery(page, size));
        // One read for the page's Items; the amounts come from each Task's recorded settlements.
        Map<UUID, TaskItems> items = readAppService.findItems(owned.stream().map(TaskModel::id).toList());
        List<TaskDTO> tasks = owned.stream()
                .map(task -> TaskModel2DTOConverter.toDTO(task, null, null, null, null, items.get(task.id()), false,
                        readAppService.findSettlementRecord(task)))
                .toList();
        return ok(tasks);
    }

    @PostMapping("/{id}/accept")
    public WebResult<TaskDTO> accept(@PathVariable("id") UUID id) {
        UUID clientId = currentUser.currentUserId();
        reviewAppService.accept(id, clientId);
        return ok(withItems(readAppService.getForClient(id, clientId)));
    }

    /** Accept one Item of a multi-Item Task (spec S3); the Task resolves once every Item has settled. */
    @PostMapping("/{id}/items/{itemNo}/accept")
    public WebResult<TaskDTO> acceptItem(@PathVariable("id") UUID id, @PathVariable("itemNo") int itemNo) {
        UUID clientId = currentUser.currentUserId();
        reviewAppService.acceptItem(id, clientId, itemNo);
        return ok(withItems(readAppService.getForClient(id, clientId)));
    }

    private TaskDTO withItems(TaskModel task) {
        // Same view as GET /{id}: the page replaces its task with this, so keep the review window.
        return TaskModel2DTOConverter.toDTO(task, readAppService.findBookedAgentId(task).orElse(null),
                readAppService.findReviewWindow(task).orElse(null),
                rejectionAppService.findLatest(task.id()).orElse(null), null, readAppService.findItems(task), true,
                readAppService.findSettlementRecord(task));
    }

    /** A task with the amounts its recorded settlements say. */
    private TaskDTO settled(TaskModel task) {
        return TaskModel2DTOConverter.toDTO(task, readAppService.findSettlementRecord(task));
    }

    @PostMapping("/{id}/reject")
    public WebResult<TaskDTO> reject(@PathVariable("id") UUID id,
                                     @Valid @RequestBody RejectTaskRequest request) {
        UUID clientId = currentUser.currentUserId();
        reviewAppService.reject(id, clientId, request.reasonCategory(), request.reason());
        return ok(settled(readAppService.getForClient(id, clientId)));
    }

    /**
     * Ask for AI arbitration of a result the Agent stood by after your A/B/C rejection (#95). Only the
     * task's Client can, only while it awaits their decision (409 otherwise), and no deposit is taken.
     */
    @PostMapping("/{id}/arbitration")
    public WebResult<TaskDTO> requestArbitration(@PathVariable("id") UUID id) {
        UUID clientId = currentUser.currentUserId();
        rejectionAppService.requestArbitration(id, clientId);
        return ok(settled(readAppService.getForClient(id, clientId)));
    }

    /**
     * Rate a task you accepted. Deliberately a separate call from accept rather than a field on
     * it, so the prompt can be skipped at the moment of acceptance and answered later — a client
     * forced into a snap judgment to get on with their day mostly just skips it.
     */
    @PostMapping("/{id}/review")
    public WebResult<ReviewDTO> review(@PathVariable("id") UUID id,
                                       @Valid @RequestBody CreateReviewRequest request) {
        UUID clientId = currentUser.currentUserId();
        return ok(ReviewDTO.from(
                ratingAppService.review(id, clientId, request.rating(), request.reviewText())));
    }

    /**
     * A POST so the task text stays out of URLs. {@code exclude} names Agents to leave out of both
     * lists: the web rebook passes the Agent that just failed the task (#79). {@code deadlineSeconds}
     * leaves out Agents that can't finish in time, counted from this request (#83). {@code title} and
     * {@code description} are the task text the relevance floor and ranking compare profiles with (#85).
     * {@code contract} (optional) keeps only Agents whose Offer covers it; an empty shortlist comes with
     * {@code noMatch}, naming what excluded the Agents and how relaxing it would help (#91).
     */
    @PostMapping("/match-preview")
    public WebResult<MatchPreviewDTO> matchPreview(@Valid @RequestBody MatchPreviewRequest request) {
        return ok(MatchPreview2DTOConverter.toDTO(matchPreviewAppService.preview(
                request.title(), request.description(), request.category(), Money.of(request.budget()), excludedAgents(request.exclude()),
                request.deadlineSeconds(), contractOf(request.contract()))));
    }

    private static Set<UUID> excludedAgents(List<UUID> exclude) {
        if (exclude == null) {
            return Set.of();
        }
        if (exclude.size() > MAX_EXCLUDED_AGENTS) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "exclude: at most " + MAX_EXCLUDED_AGENTS + " agents");
        }
        return Set.copyOf(exclude);
    }
}
