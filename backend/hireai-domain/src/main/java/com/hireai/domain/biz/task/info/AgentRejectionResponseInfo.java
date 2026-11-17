package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.task.rejection.enums.AgentResponse;

/**
 * An Agent's response to a rejection notice (#95): the action, the stand-by reason (STAND_BY only),
 * and for a revision the revised result in the same fields as a result callback.
 */
public record AgentRejectionResponseInfo(AgentResponse action, String reason, String resultPayloadJson,
                                         String resultUrl) {
}
