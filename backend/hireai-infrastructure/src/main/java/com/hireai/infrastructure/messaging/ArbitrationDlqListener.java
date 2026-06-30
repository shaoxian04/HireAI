package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 兜底: an exhausted/poison arbitration request dead-letters here → full refund to the client + dispute RESOLVED. */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ArbitrationDlqListener {

    private final DisputeAppService disputeAppService;

    @RabbitListener(queues = ArbitrationQueues.DLQ)
    public void onDeadLetter(ArbitrationRequestMessage message) {
        log.warn("Arbitration request dead-lettered for dispute {} (correlation {}); refund-fallback",
                message.disputeId(), message.correlationId());
        disputeAppService.resolveByFallback(message.disputeId());
    }
}
