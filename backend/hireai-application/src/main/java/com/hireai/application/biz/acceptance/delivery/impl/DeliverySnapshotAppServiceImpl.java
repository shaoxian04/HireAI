package com.hireai.application.biz.acceptance.delivery.impl;

import com.hireai.application.biz.acceptance.delivery.DeliverySnapshotAppService;
import com.hireai.application.biz.adjudication.port.ArbitrationDeliverable;
import com.hireai.application.port.delivery.DeliverableFetchPort;
import com.hireai.application.port.delivery.DeliveryContentStore;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryFetch;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import com.hireai.domain.biz.acceptance.delivery.repository.DeliverySnapshotRepository;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.model.TaskResultModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

@Slf4j
@Service
@Transactional
public class DeliverySnapshotAppServiceImpl implements DeliverySnapshotAppService {

    private final DeliverableFetchPort fetchPort;
    private final DeliveryContentStore contentStore;
    private final DeliverySnapshotRepository snapshotRepository;
    private final ValidationDomainService validationDomainService;
    private final Clock clock;
    private final long platformMaxBytes;
    private final long inlineArbitrationMaxBytes;

    public DeliverySnapshotAppServiceImpl(DeliverableFetchPort fetchPort, DeliveryContentStore contentStore,
                                          DeliverySnapshotRepository snapshotRepository,
                                          ValidationDomainService validationDomainService, Clock clock,
                                          @Value("${hireai.delivery.max-bytes:26214400}") long platformMaxBytes,
                                          @Value("${hireai.arbitration.inline-deliverable-max-bytes:8388608}")
                                          long inlineArbitrationMaxBytes) {
        this.fetchPort = fetchPort;
        this.contentStore = contentStore;
        this.snapshotRepository = snapshotRepository;
        this.validationDomainService = validationDomainService;
        this.clock = clock;
        this.platformMaxBytes = platformMaxBytes;
        this.inlineArbitrationMaxBytes = inlineArbitrationMaxBytes;
    }

    @Override
    public DeliveryOutcome capture(Contract contract, TaskResultModel result) {
        Optional<DeliverySnapshot> existing = snapshotRepository.findByTaskResultId(result.id());
        if (existing.isPresent()) {
            return new DeliveryOutcome.Captured(existing.get());
        }
        long limit = validationDomainService.deliveryByteLimit(contract, platformMaxBytes);
        DeliveryFetch fetch = fetchPort.fetch(result.resultUrl(), limit);
        return switch (fetch) {
            case DeliveryFetch.Fetched fetched -> {
                DeliverySnapshot snapshot = DeliverySnapshot.capture(result.taskId(), result.id(),
                        result.resultUrl(), fetched, clock.instant());
                contentStore.put(snapshot.sha256(), fetched.content());
                snapshotRepository.save(snapshot);
                log.info("Task {} deliverable frozen: {} bytes, {}, sha256 {}", result.taskId(),
                        snapshot.sizeBytes(), snapshot.mediaType(), snapshot.sha256());
                yield new DeliveryOutcome.Captured(snapshot);
            }
            case DeliveryOutcome.TooLarge tooLarge -> {
                log.info("Task {} deliverable is over {} bytes; nothing kept", result.taskId(), tooLarge.limitBytes());
                yield tooLarge;
            }
            case DeliveryOutcome.Unavailable unavailable -> {
                log.info("Task {} deliverable could not be fetched: {}", result.taskId(), unavailable.reason());
                yield unavailable;
            }
        };
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeliverySnapshot> findForResult(TaskResultModel result) {
        return snapshotRepository.findByTaskResultId(result.id());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> content(DeliverySnapshot snapshot) {
        return contentStore.get(snapshot.sha256());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArbitrationDeliverable> forArbitration(TaskResultModel result) {
        if (result == null) {
            return Optional.empty();
        }
        return findForResult(result).map(snapshot -> {
            String inline = snapshot.sizeBytes() <= inlineArbitrationMaxBytes
                    ? content(snapshot).map(bytes -> Base64.getEncoder().encodeToString(bytes)).orElse(null)
                    : null;
            return new ArbitrationDeliverable(snapshot.sha256(), snapshot.mediaType(), snapshot.sizeBytes(), inline);
        });
    }
}
