package com.hireai.application.biz.adjudication.port;

import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;

import java.util.List;
import java.util.UUID;

/** Read-only projection port for a user's own disputes lists. */
public interface DisputeQueryPort {

    /** Disputes the Client raised. */
    List<DisputeMineRow> findDisputesForClient(UUID clientId);

    /** Disputes on tasks run by Agents the Builder owns. */
    List<DisputeMineRow> findDisputesForBuilder(UUID builderId);
}
