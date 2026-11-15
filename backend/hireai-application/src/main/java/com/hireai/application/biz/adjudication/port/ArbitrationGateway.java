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

    /**
     * @param deliverable the task result's frozen DeliverySnapshot (D24), or null when the result has
     *                    none (not a file, or a Task from before snapshots). The arbitrator judges these
     *                    bytes, never the Agent's link.
     */
    Optional<RulingInfo> requestRuling(DisputeModel dispute, TaskModel task, ArbitrationDeliverable deliverable);
}
