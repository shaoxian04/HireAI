package com.hireai.controller.biz.adjudication;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.dispute.DisputeReadAppService;
import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.adjudication.dto.DisputeMineRowDTO;
import com.hireai.controller.biz.adjudication.dto.DisputeOutcomeDTO;
import com.hireai.controller.config.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/disputes")
public class DisputeController extends BaseController {

    private final DisputeReadAppService disputeReadAppService;
    private final DisputeAppService disputeAppService;
    private final CurrentUserProvider currentUser;

    public DisputeController(DisputeReadAppService disputeReadAppService,
                             DisputeAppService disputeAppService,
                             CurrentUserProvider currentUser) {
        this.disputeReadAppService = disputeReadAppService;
        this.disputeAppService = disputeAppService;
        this.currentUser = currentUser;
    }

    @GetMapping("/by-task/{taskId}")
    public WebResult<DisputeOutcomeDTO> getByTask(@PathVariable("taskId") UUID taskId) {
        UUID userId = currentUser.currentUserId();
        DisputeOutcomeDTO dto = Dispute2DTOConverter.toDTO(
                disputeReadAppService.getOutcomeForUser(taskId, userId));
        return ok(dto);
    }

    @PostMapping("/{id}/accept-ruling")
    public WebResult<DisputeOutcomeDTO> acceptRuling(@PathVariable("id") UUID id) {
        UUID userId = currentUser.currentUserId();
        disputeAppService.acceptRuling(id, userId);
        return ok(Dispute2DTOConverter.toDTO(disputeReadAppService.getOutcomeByDispute(id, userId)));
    }

    @PostMapping("/{id}/appeal")
    public WebResult<DisputeOutcomeDTO> appeal(@PathVariable("id") UUID id) {
        UUID userId = currentUser.currentUserId();
        disputeAppService.appeal(id, userId);
        return ok(Dispute2DTOConverter.toDTO(disputeReadAppService.getOutcomeByDispute(id, userId)));
    }

    /** Disputes the current user raised as a Client. */
    @GetMapping("/mine")
    public WebResult<List<DisputeMineRowDTO>> mine() {
        return ok(rows(disputeReadAppService.myDisputes(currentUser.currentUserId())));
    }

    /** Disputes on tasks run by the current user's Agents, as a Builder (#96). */
    @GetMapping("/builder")
    public WebResult<List<DisputeMineRowDTO>> builder() {
        return ok(rows(disputeReadAppService.builderDisputes(currentUser.currentUserId())));
    }

    private static List<DisputeMineRowDTO> rows(List<DisputeMineRow> rows) {
        return rows.stream()
                .map(r -> new DisputeMineRowDTO(r.disputeId(), r.taskId(), r.taskTitle(), r.status(),
                        r.proposedCategory(), r.updatedAt(), r.awaitingYou()))
                .toList();
    }
}
