package com.hireai.controller.biz.adjudication;

import com.hireai.application.biz.adjudication.dispute.DisputeReadAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.adjudication.dto.DisputeOutcomeDTO;
import com.hireai.controller.config.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/disputes")
public class DisputeController extends BaseController {

    private final DisputeReadAppService disputeReadAppService;
    private final CurrentUserProvider currentUser;

    public DisputeController(DisputeReadAppService disputeReadAppService,
                             CurrentUserProvider currentUser) {
        this.disputeReadAppService = disputeReadAppService;
        this.currentUser = currentUser;
    }

    @GetMapping("/by-task/{taskId}")
    public WebResult<DisputeOutcomeDTO> getByTask(@PathVariable("taskId") UUID taskId) {
        UUID userId = currentUser.currentUserId();
        DisputeOutcomeDTO dto = Dispute2DTOConverter.toDTO(
                disputeReadAppService.getOutcomeForUser(taskId, userId));
        return ok(dto);
    }
}
