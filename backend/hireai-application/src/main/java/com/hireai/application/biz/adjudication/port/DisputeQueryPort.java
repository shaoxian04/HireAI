package com.hireai.application.biz.adjudication.port;

import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;

import java.util.List;
import java.util.UUID;

/** Read-only projection port for the client's own disputes list. */
public interface DisputeQueryPort {

    List<DisputeMineRow> findDisputesForClient(UUID clientId);
}
