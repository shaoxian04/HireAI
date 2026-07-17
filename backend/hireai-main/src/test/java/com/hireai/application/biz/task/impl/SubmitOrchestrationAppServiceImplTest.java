package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.task.SubmitContext;
import com.hireai.application.biz.task.SubmitOrchestrationAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.query.SpendReadPort;
import com.hireai.domain.biz.apikey.model.IdempotencyRecord;
import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.apikey.repository.IdempotencyRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmitOrchestrationAppServiceImplTest {

    private final TaskWriteAppService write = mock(TaskWriteAppService.class);
    private final DirectBookingAppService direct = mock(DirectBookingAppService.class);
    private final IdempotencyRepository idem = mock(IdempotencyRepository.class);
    private final ApiKeyTaskRepository attribution = mock(ApiKeyTaskRepository.class);
    private final SpendReadPort spendRead = mock(SpendReadPort.class);
    private final Instant fixed = Instant.parse("2026-07-15T10:00:00Z");

    private final SubmitOrchestrationAppService svc = new SubmitOrchestrationAppServiceImpl(
            write, direct, idem, attribution, spendRead, Clock.fixed(fixed, ZoneOffset.UTC));

    private TaskSubmitInfo info(UUID owner, String budget) {
        return new TaskSubmitInfo(owner, "T", "desc", Money.of(budget),
                new OutputSpec(OutputFormat.JSON, "{}", "ok"), "cat");
    }

    // ---- idempotency ----

    @Test
    void firstSubmitWithKeyPersistsRecordAndAttributes() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SubmitContext ctx = new SubmitContext(owner, "idem-1", keyId, null, null);
        when(idem.find(owner, "idem-1")).thenReturn(Optional.empty());
        when(write.submit(any())).thenReturn(taskId);

        UUID result = svc.submitRouted(ctx, info(owner, "20.00"));

        assertThat(result).isEqualTo(taskId);
        verify(write).submit(any());
        verify(idem).insert(any(IdempotencyRecord.class));
        verify(attribution).attribute(eq(taskId), eq(keyId), any(), eq(fixed));
    }

    @Test
    void replayWithSameKeyAndFingerprintReturnsExistingTaskWithoutResubmitting() {
        UUID owner = UUID.randomUUID();
        UUID existing = UUID.randomUUID();
        TaskSubmitInfo info = info(owner, "20.00");
        // Stub find() to return a record whose fingerprint EQUALS what the service will compute
        // (fingerprintFor mirrors SubmitOrchestrationAppServiceImpl.fingerprint exactly).
        when(idem.find(eq(owner), eq("idem-1"))).thenReturn(Optional.of(
                new IdempotencyRecord(UUID.randomUUID(), owner, "idem-1",
                        fingerprintFor(info), existing, fixed)));
        SubmitContext ctx = new SubmitContext(owner, "idem-1", null, null, null);

        UUID result = svc.submitRouted(ctx, info);

        assertThat(result).isEqualTo(existing);
        verify(write, never()).submit(any());
        verify(idem, never()).insert(any());
    }

    @Test
    void replayWithSameKeyButDifferentFingerprintIs409() {
        UUID owner = UUID.randomUUID();
        when(idem.find(eq(owner), eq("idem-1"))).thenReturn(Optional.of(
                new IdempotencyRecord(UUID.randomUUID(), owner, "idem-1",
                        "a-totally-different-fingerprint", UUID.randomUUID(), fixed)));
        SubmitContext ctx = new SubmitContext(owner, "idem-1", null, null, null);

        assertThatThrownBy(() -> svc.submitRouted(ctx, info(owner, "20.00")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.IDEMPOTENCY_CONFLICT));
        verify(write, never()).submit(any());
    }

    @Test
    void concurrentInsertRaceReReadsWinnerAndReturnsItsTask() {
        UUID owner = UUID.randomUUID();
        UUID winner = UUID.randomUUID();
        TaskSubmitInfo info = info(owner, "20.00");
        SubmitContext ctx = new SubmitContext(owner, "idem-1", null, null, null);
        // Pre-check finds nothing; submit runs; insert loses the UNIQUE race; re-read finds the winner.
        when(idem.find(eq(owner), eq("idem-1")))
                .thenReturn(Optional.empty())                              // pre-check
                .thenReturn(Optional.of(new IdempotencyRecord(UUID.randomUUID(), owner, "idem-1",
                        fingerprintFor(info), winner, fixed)));            // re-read after violation
        when(write.submit(any())).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("dup"))
                .when(idem).insert(any());

        UUID result = svc.submitRouted(ctx, info);

        assertThat(result).isEqualTo(winner);
    }

    // ---- spend cap ----

    @Test
    void concurrentCapExceededIs409AndNeverSubmits() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        SubmitContext ctx = new SubmitContext(owner, null, keyId, new BigDecimal("100.00"), null);
        when(spendRead.committedFor(keyId)).thenReturn(new BigDecimal("90.00"));

        assertThatThrownBy(() -> svc.submitRouted(ctx, info(owner, "20.00")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.SPEND_CAP_EXCEEDED));
        verify(write, never()).submit(any());
    }

    @Test
    void dailyCapExceededIs409() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        SubmitContext ctx = new SubmitContext(owner, null, keyId, null, new BigDecimal("50.00"));
        when(spendRead.dailySpendFor(eq(keyId), eq(fixed.minusSeconds(86400))))
                .thenReturn(new BigDecimal("40.00"));

        assertThatThrownBy(() -> svc.submitRouted(ctx, info(owner, "20.00")))
                .isInstanceOf(DomainException.class);
        verify(write, never()).submit(any());
    }

    @Test
    void uncappedKeyNeverReadsSpendAndSubmits() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        SubmitContext ctx = new SubmitContext(owner, null, keyId, null, null);
        when(write.submit(any())).thenReturn(taskId);

        assertThat(svc.submitRouted(ctx, info(owner, "20.00"))).isEqualTo(taskId);
        verify(spendRead, never()).committedFor(any());
        verify(spendRead, never()).dailySpendFor(any(), any());
    }

    // ---- direct booking uses the same guards ----

    @Test
    void submitDirectAppliesGuardsAndDelegatesToBooking() {
        UUID owner = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        DirectBookingInfo info = new DirectBookingInfo(owner, "T", "d", Money.of("20.00"), UUID.randomUUID());
        SubmitContext ctx = new SubmitContext(owner, null, keyId, null, null);
        when(direct.book(any())).thenReturn(taskId);

        assertThat(svc.submitDirect(ctx, info)).isEqualTo(taskId);
        verify(direct).book(info);
        verify(attribution).attribute(eq(taskId), eq(keyId), any(), eq(fixed));
    }

    // Mirrors SubmitOrchestrationAppServiceImpl.fingerprint(TaskSubmitInfo) + specJson(OutputSpec)
    // BYTE-FOR-BYTE. If you change the impl's rendering, change this too.
    private static String fingerprintFor(TaskSubmitInfo i) {
        OutputSpec s = i.outputSpec();
        String specJson = s == null ? "∅"
                : s.format() + "|" + nz(s.schema()) + "|" + nz(s.acceptanceCriteria());
        return com.hireai.application.biz.task.SubmitFingerprint.of(
                i.title(), i.description(), i.category(), i.budget().value(), specJson);
    }

    private static String nz(String s) { return s == null ? "∅" : s; }
}
