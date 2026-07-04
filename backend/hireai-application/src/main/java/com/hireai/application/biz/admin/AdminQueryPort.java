package com.hireai.application.biz.admin;

import com.hireai.application.biz.admin.view.AdminViews;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only cross-aggregate projections for the admin surface (implemented by a JDBC DAO). */
public interface AdminQueryPort {

    AdminViews.Overview overview();

    List<AdminViews.DisputeRow> disputeQueue(boolean needsAttentionOnly);

    Optional<AdminViews.Evidence> disputeEvidence(UUID taskId);

    List<AdminViews.TaskRow> recentTasks(int limit);

    List<AdminViews.UserRow> usersWithWallets();

    List<AdminViews.AgentRow> agents();
}
