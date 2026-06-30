// ArbitrationGateway.java
package com.hireai.application.biz.adjudication.port;

import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.model.TaskModel;

import java.util.Optional;

/**
 * ACL port to the arbitration capability. Returns a present {@link RulingInfo} when the ruling is
 * produced synchronously (the Phase-2 stub / tests), or empty when the request was handed off for
 * asynchronous arbitration (the Phase-3 RabbitMQ adapter), in which case the ruling arrives later
 * via the arbitration ruling callback.
 */
public interface ArbitrationGateway {
    Optional<RulingInfo> requestRuling(DisputeModel dispute, TaskModel task);
}
