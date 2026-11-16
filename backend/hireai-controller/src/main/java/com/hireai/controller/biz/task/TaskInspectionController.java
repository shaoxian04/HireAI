package com.hireai.controller.biz.task;

import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService;
import com.hireai.application.biz.task.RejectionAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.sampling.SamplingAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.task.converter.TaskModel2DTOConverter;
import com.hireai.controller.biz.task.dto.InspectionDTO;
import com.hireai.controller.biz.task.dto.ItemDecisionRequest;
import com.hireai.controller.biz.task.dto.JudgeItemRequest;
import com.hireai.controller.biz.task.dto.TaskDTO;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.utility.result.ResultCode;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Sampling inspection of a multi-Item Task (spec S8) for its buyer's reviewer: the inspection and its
 * samples, judging an Item, deciding on an Item the Agent stood by, and an Item's source document.
 * Human-only (the security chain's default): a reviewer is a person, never a script or an LLM (D4).
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskInspectionController extends BaseController {

    /** Types a source document is served as; anything else (HTML and SVG included) is octet-stream. */
    private static final Set<String> SOURCE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif",
            "application/pdf", "application/json", "text/csv", "text/plain");

    private final SamplingAppService samplingAppService;
    private final TaskReadAppService readAppService;
    private final RejectionAppService rejectionAppService;
    private final CurrentUserProvider currentUser;

    public TaskInspectionController(SamplingAppService samplingAppService, TaskReadAppService readAppService,
                                    RejectionAppService rejectionAppService, CurrentUserProvider currentUser) {
        this.samplingAppService = samplingAppService;
        this.readAppService = readAppService;
        this.rejectionAppService = rejectionAppService;
        this.currentUser = currentUser;
    }

    /** The Task's sampling inspection; {@code data} is null for a Task that is not sampled. */
    @GetMapping("/{id}/inspection")
    public WebResult<InspectionDTO> inspection(@PathVariable("id") UUID id) {
        UUID clientId = currentUser.currentUserId();
        return ok(samplingAppService.inspectionFor(id, clientId).map(InspectionDTO::from).orElse(null));
    }

    /**
     * The reviewer judges one Item: a pass accepts and pays it; a fail (A/B/C + what is wrong) opens its
     * rejection response path. A sampled Item's judgement counts towards the sample; any other Item is
     * an extra check, handled on its own. 409 once the review window closed or when the Item is not
     * awaiting review.
     */
    @PostMapping("/{id}/items/{itemNo}/judgement")
    public WebResult<TaskDTO> judgeItem(@PathVariable("id") UUID id, @PathVariable("itemNo") int itemNo,
                                        @Valid @RequestBody JudgeItemRequest request) {
        UUID clientId = currentUser.currentUserId();
        samplingAppService.judge(id, clientId, itemNo, request.passed(), request.reasonCategory(), request.reason());
        return ok(withItems(readAppService.getForClient(id, clientId)));
    }

    /**
     * The buyer's decision on an Item the Agent stood by (spec D17′): ACCEPT pays it. ARBITRATE is refused
     * (409) until single Items can be arbitrated; the decision window's close accepts it.
     */
    @PostMapping("/{id}/items/{itemNo}/decision")
    public WebResult<TaskDTO> decideItem(@PathVariable("id") UUID id, @PathVariable("itemNo") int itemNo,
                                         @Valid @RequestBody ItemDecisionRequest request) {
        UUID clientId = currentUser.currentUserId();
        samplingAppService.decideItem(id, clientId, itemNo, request.decision());
        return ok(withItems(readAppService.getForClient(id, clientId)));
    }

    /**
     * An Item's source document, to compare the Agent's result against. Served as an attachment a browser
     * never runs; 410 once retention deleted it (only its hash is kept).
     */
    @GetMapping("/{id}/items/{itemNo}/source")
    public ResponseEntity<?> itemSource(@PathVariable("id") UUID id, @PathVariable("itemNo") int itemNo) {
        UUID clientId = currentUser.currentUserId();
        return switch (samplingAppService.sourceFor(id, clientId, itemNo)) {
            case SourceSnapshotAppService.Download.Served served -> ResponseEntity.ok()
                    .contentType(SOURCE_TYPES.contains(served.snapshot().mediaType())
                            ? MediaType.parseMediaType(served.snapshot().mediaType())
                            : MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(served.content().length)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(served.snapshot().filename(), StandardCharsets.UTF_8).build().toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "sandbox; default-src 'none'")
                    .header("X-HireAI-Sha256", served.snapshot().sha256())
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .body(served.content());
            case SourceSnapshotAppService.Download.Refused refused -> ResponseEntity.status(HttpStatus.GONE)
                    .body(WebResult.error(ResultCode.VALIDATION_ERROR, "This document was deleted at the end of "
                            + "its retention period; only its hash is kept"));
        };
    }

    /** Same view as GET /api/tasks/{id}: the page replaces its task with this. */
    private TaskDTO withItems(TaskModel task) {
        return TaskModel2DTOConverter.toDTO(task, readAppService.findBookedAgentId(task).orElse(null),
                readAppService.findReviewWindow(task).orElse(null),
                rejectionAppService.findLatest(task.id()).orElse(null), null, readAppService.findItems(task), true);
    }
}
