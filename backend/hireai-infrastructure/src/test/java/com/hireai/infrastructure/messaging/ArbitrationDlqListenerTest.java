package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;

class ArbitrationDlqListenerTest {

    private final DisputeAppService disputeAppService = mock(DisputeAppService.class);
    private final ArbitrationDlqListener listener = new ArbitrationDlqListener(disputeAppService);

    @Test
    void deadLetteredRequestEscalatesToAdmin() {
        UUID disputeId = UUID.randomUUID();
        ArbitrationRequestMessage msg = new ArbitrationRequestMessage(
                disputeId, UUID.randomUUID(), "corr", "JSON", null, null, null, "{}", null, "A_MISMATCH");
        listener.onDeadLetter(msg);
        verify(disputeAppService).escalate(disputeId);
    }
}
