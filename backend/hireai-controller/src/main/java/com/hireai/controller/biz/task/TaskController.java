package com.hireai.controller.biz.task;

import com.hireai.application.biz.adjudication.validation.ValidationReadAppService;
import com.hireai.application.biz.task.MatchPreviewAppService;
import com.hireai.application.biz.task.SubmitContext;
import com.hireai.application.biz.task.SubmitOrchestrationAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.adjudication.ValidationReport2DTOConverter;
import com.hireai.controller.biz.adjudication.dto.ValidationReportDTO;
import com.hireai.controller.biz.task.converter.MatchPreview2DTOConverter;
import com.hireai.controller.biz.task.converter.TaskModel2DTOConverter;
import com.hireai.controller.biz.task.converter.TaskResult2DTOConverter;
import com.hireai.controller.biz.task.dto.DirectBookRequest;
import com.hireai.controller.biz.task.dto.MatchPreviewDTO;
import com.hireai.controller.biz.task.dto.RejectTaskRequest;
import com.hireai.controller.biz.task.dto.SubmitTaskRequest;
import com.hireai.controller.biz.task.dto.TaskDTO;
import com.hireai.controller.biz.task.dto.TaskResultDTO;
import com.hireai.controller.config.ApiKeyContext;
import com.hireai.controller.config.CurrentApiKeyProvider;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
    private final MatchPreviewAppService matchPreviewAppService;
    private final ValidationReadAppService validationReadAppService;

    public TaskController(SubmitOrchestrationAppService submitOrchestrationAppService,
                          TaskReadAppService readAppService,
                          CurrentUserProvider currentUser,
                          CurrentApiKeyProvider currentApiKey,
                          TaskReviewAppService reviewAppService,
                          MatchPreviewAppService matchPreviewAppService,
                          ValidationReadAppService validationReadAppService) {
        this.submitOrchestrationAppService = submitOrchestrationAppService;
        this.readAppService = readAppService;
        this.currentUser = currentUser;
        this.currentApiKey = currentApiKey;
        this.reviewAppService = reviewAppService;
        this.matchPreviewAppService = matchPreviewAppService;
        this.validationReadAppService = validationReadAppService;
    }

    @PostMapping
    public WebResult<TaskDTO> submit(@Valid @RequestBody SubmitTaskRequest request,
                                     @RequestHeader(value = "Idempotency-Key", required = false)
                                     String idempotencyKey) {
        UUID clientId = currentUser.currentUserId();
        SubmitTaskRequest.OutputSpecRequest specRequest = request.outputSpec();
        TaskSubmitInfo info = new TaskSubmitInfo(
                clientId,
                request.title(),
                request.description(),
                Money.of(request.budget()),
                new OutputSpec(specRequest.format(), specRequest.schema(), specRequest.acceptanceCriteria()),
                request.category());
        UUID taskId = submitOrchestrationAppService.submitRouted(
                submitContext(clientId, idempotencyKey), info);
        TaskDTO dto = TaskModel2DTOConverter.toDTO(readAppService.getForClient(taskId, clientId));
        return ok(dto);
    }

    @PostMapping("/direct")
    public WebResult<TaskDTO> bookDirect(@Valid @RequestBody DirectBookRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false)
                                         String idempotencyKey) {
        UUID clientId = currentUser.currentUserId();
        UUID taskId = submitOrchestrationAppService.submitDirect(
                submitContext(clientId, idempotencyKey),
                new DirectBookingInfo(clientId, request.title(), request.description(),
                        Money.of(request.budget()), request.agentId()));
        return ok(TaskModel2DTOConverter.toDTO(readAppService.getForClient(taskId, clientId)));
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
        TaskDTO dto = TaskModel2DTOConverter.toDTO(readAppService.getForClient(id, clientId));
        return ok(dto);
    }

    @GetMapping("/{taskId}/result")
    public WebResult<TaskResultDTO> getResult(@PathVariable("taskId") UUID taskId) {
        UUID clientId = currentUser.currentUserId();
        TaskResultDTO dto = TaskResult2DTOConverter.toDTO(readAppService.getResult(taskId, clientId));
        return ok(dto);
    }

    @GetMapping("/{id}/validation")
    public WebResult<ValidationReportDTO> getValidation(@PathVariable("id") UUID id) {
        UUID clientId = currentUser.currentUserId();
        readAppService.getForClient(id, clientId); // ownership guard (Hard Invariant #5)
        ValidationReportModel report = validationReadAppService.latestForTask(id)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No validation report for task: " + id));
        return ok(ValidationReport2DTOConverter.toDTO(report));
    }

    @GetMapping
    public WebResult<List<TaskDTO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID clientId = currentUser.currentUserId();
        List<TaskDTO> tasks = readAppService.listForClient(clientId, new TaskQuery(page, size))
                .stream()
                .map(TaskModel2DTOConverter::toDTO)
                .toList();
        return ok(tasks);
    }

    @PostMapping("/{id}/accept")
    public WebResult<TaskDTO> accept(@PathVariable("id") UUID id) {
        UUID clientId = currentUser.currentUserId();
        reviewAppService.accept(id, clientId);
        return ok(TaskModel2DTOConverter.toDTO(readAppService.getForClient(id, clientId)));
    }

    @PostMapping("/{id}/reject")
    public WebResult<TaskDTO> reject(@PathVariable("id") UUID id,
                                     @Valid @RequestBody RejectTaskRequest request) {
        UUID clientId = currentUser.currentUserId();
        reviewAppService.reject(id, clientId, request.reasonCategory(), request.reason());
        return ok(TaskModel2DTOConverter.toDTO(readAppService.getForClient(id, clientId)));
    }

    @GetMapping("/match-preview")
    public WebResult<MatchPreviewDTO> matchPreview(@RequestParam("category") String category,
                                                   @RequestParam("budget") BigDecimal budget) {
        if (category == null || category.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "category is required");
        }
        if (budget == null || budget.signum() <= 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "budget must be positive");
        }
        return ok(MatchPreview2DTOConverter.toDTO(matchPreviewAppService.preview(category, Money.of(budget))));
    }
}
