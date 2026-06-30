package com.hireai.controller.biz.task;

import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskReviewAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.task.converter.TaskModel2DTOConverter;
import com.hireai.controller.biz.task.converter.TaskResult2DTOConverter;
import com.hireai.controller.biz.task.dto.DirectBookRequest;
import com.hireai.controller.biz.task.dto.RejectTaskRequest;
import com.hireai.controller.biz.task.dto.SubmitTaskRequest;
import com.hireai.controller.biz.task.dto.TaskDTO;
import com.hireai.controller.biz.task.dto.TaskResultDTO;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.repository.TaskQuery;
import com.hireai.domain.shared.model.Money;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Task HTTP surface. Thin: validate the request, resolve identity server-side, build the
 * domain carrier, call one app service, wrap the result. Identity comes from
 * {@link CurrentUserProvider} (the JWT principal once auth lands) — never from path or body.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController extends BaseController {

    private final TaskWriteAppService writeAppService;
    private final TaskReadAppService readAppService;
    private final CurrentUserProvider currentUser;
    private final DirectBookingAppService directBookingAppService;
    private final TaskReviewAppService reviewAppService;

    public TaskController(TaskWriteAppService writeAppService,
                          TaskReadAppService readAppService,
                          CurrentUserProvider currentUser,
                          DirectBookingAppService directBookingAppService,
                          TaskReviewAppService reviewAppService) {
        this.writeAppService = writeAppService;
        this.readAppService = readAppService;
        this.currentUser = currentUser;
        this.directBookingAppService = directBookingAppService;
        this.reviewAppService = reviewAppService;
    }

    @PostMapping
    public WebResult<TaskDTO> submit(@Valid @RequestBody SubmitTaskRequest request) {
        UUID clientId = currentUser.currentUserId();
        SubmitTaskRequest.OutputSpecRequest specRequest = request.outputSpec();
        TaskSubmitInfo info = new TaskSubmitInfo(
                clientId,
                request.title(),
                request.description(),
                Money.of(request.budget()),
                new OutputSpec(specRequest.format(), specRequest.schema(), specRequest.acceptanceCriteria()),
                request.category());
        UUID taskId = writeAppService.submit(info);
        TaskDTO dto = TaskModel2DTOConverter.toDTO(readAppService.getForClient(taskId, clientId));
        return ok(dto);
    }

    @PostMapping("/direct")
    public WebResult<TaskDTO> bookDirect(@Valid @RequestBody DirectBookRequest request) {
        UUID clientId = currentUser.currentUserId();
        UUID taskId = directBookingAppService.book(new DirectBookingInfo(
                clientId, request.title(), request.description(),
                Money.of(request.budget()), request.agentId()));
        return ok(TaskModel2DTOConverter.toDTO(readAppService.getForClient(taskId, clientId)));
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
}
