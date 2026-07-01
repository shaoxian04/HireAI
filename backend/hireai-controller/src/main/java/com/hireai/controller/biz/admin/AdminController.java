package com.hireai.controller.biz.admin;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.admin.AdminReadAppService;
import com.hireai.application.biz.admin.view.AdminViews;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.admin.dto.AdminRuleRequest;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
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

/** Admin surface: read-only overview/browsers + the tier-2 human-backstop ruling. Gated by ROLE_ADMIN. */
@RestController
@RequestMapping("/api/admin")
public class AdminController extends BaseController {

    private final AdminReadAppService adminReadAppService;
    private final DisputeAppService disputeAppService;
    private final CurrentUserProvider currentUser;

    public AdminController(AdminReadAppService adminReadAppService, DisputeAppService disputeAppService,
                           CurrentUserProvider currentUser) {
        this.adminReadAppService = adminReadAppService;
        this.disputeAppService = disputeAppService;
        this.currentUser = currentUser;
    }

    @GetMapping("/overview")
    public WebResult<AdminViews.Overview> overview() {
        return ok(adminReadAppService.overview());
    }

    @GetMapping("/disputes")
    public WebResult<List<AdminViews.DisputeRow>> disputes(
            @RequestParam(value = "filter", defaultValue = "needs_attention") String filter) {
        boolean needsAttentionOnly = !"all".equalsIgnoreCase(filter);
        return ok(adminReadAppService.disputeQueue(needsAttentionOnly));
    }

    @GetMapping("/disputes/{id}")
    public WebResult<AdminViews.DisputeDetail> disputeDetail(@PathVariable("id") UUID id) {
        return ok(adminReadAppService.disputeDetail(id));
    }

    @PostMapping("/disputes/{id}/rule")
    public WebResult<AdminViews.DisputeDetail> rule(@PathVariable("id") UUID id,
                                                    @Valid @RequestBody AdminRuleRequest request) {
        RulingCategory category;
        try {
            category = RulingCategory.valueOf(request.category());
        } catch (IllegalArgumentException e) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Invalid ruling category: " + request.category());
        }
        disputeAppService.adminRule(id, category, request.rationale(), currentUser.currentUserId());
        return ok(adminReadAppService.disputeDetail(id));
    }

    @GetMapping("/tasks")
    public WebResult<List<AdminViews.TaskRow>> tasks() {
        return ok(adminReadAppService.recentTasks());
    }

    @GetMapping("/users")
    public WebResult<List<AdminViews.UserRow>> users() {
        return ok(adminReadAppService.users());
    }

    @GetMapping("/agents")
    public WebResult<List<AdminViews.AgentRow>> agents() {
        return ok(adminReadAppService.agents());
    }
}
