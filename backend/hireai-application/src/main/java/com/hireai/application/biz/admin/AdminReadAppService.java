package com.hireai.application.biz.admin;

import com.hireai.application.biz.admin.view.AdminViews;
import com.hireai.application.biz.task.TaskReadAppService;

import java.util.List;
import java.util.UUID;

/** Admin read orchestration. Overview/browsers delegate to the query port; detail joins the dispute aggregate. */
public interface AdminReadAppService {

    AdminViews.Overview overview();

    List<AdminViews.DisputeRow> disputeQueue(boolean needsAttentionOnly);

    AdminViews.DisputeDetail disputeDetail(UUID disputeId);

    /** The disputed result's frozen file (D24); NOT_FOUND when there is none or it is no longer kept. */
    TaskReadAppService.FrozenDeliverable disputeDeliverable(UUID disputeId);

    List<AdminViews.TaskRow> recentTasks();

    List<AdminViews.UserRow> users();

    List<AdminViews.AgentRow> agents();
}
