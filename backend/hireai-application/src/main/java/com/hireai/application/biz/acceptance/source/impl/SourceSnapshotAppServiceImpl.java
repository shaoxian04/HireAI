package com.hireai.application.biz.acceptance.source.impl;

import com.hireai.application.biz.acceptance.engagement.EngagementAppService;
import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService;
import com.hireai.application.port.delivery.DeliveryContentStore;
import com.hireai.application.port.security.SourceLinkSigner;
import com.hireai.domain.biz.acceptance.engagement.model.EngagementModel;
import com.hireai.domain.biz.acceptance.engagement.repository.EngagementRepository;
import com.hireai.domain.biz.acceptance.source.enums.SourceDownloadOutcome;
import com.hireai.domain.biz.acceptance.source.model.SourceDownloadModel;
import com.hireai.domain.biz.acceptance.source.model.SourceSnapshotModel;
import com.hireai.domain.biz.acceptance.source.repository.SourceSnapshotRepository;
import com.hireai.domain.biz.task.item.info.DispatchItem;
import com.hireai.domain.biz.task.item.info.DispatchSource;
import com.hireai.domain.biz.task.item.model.TaskItemModel;
import com.hireai.domain.biz.task.item.repository.TaskItemRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class SourceSnapshotAppServiceImpl implements SourceSnapshotAppService {

    private final SourceSnapshotRepository sourceSnapshotRepository;
    private final EngagementRepository engagementRepository;
    private final EngagementAppService engagementAppService;
    private final TaskItemRepository taskItemRepository;
    private final DeliveryContentStore contentStore;
    private final SourceLinkSigner linkSigner;
    private final Clock clock;
    private final long maxBytes;
    private final String publicBaseUrl;

    public SourceSnapshotAppServiceImpl(SourceSnapshotRepository sourceSnapshotRepository,
                                        EngagementRepository engagementRepository,
                                        EngagementAppService engagementAppService,
                                        TaskItemRepository taskItemRepository,
                                        DeliveryContentStore contentStore,
                                        SourceLinkSigner linkSigner,
                                        Clock clock,
                                        @Value("${hireai.source.max-bytes:26214400}") long maxBytes,
                                        @Value("${hireai.platform.public-base-url:http://localhost:8080}")
                                        String publicBaseUrl) {
        this.sourceSnapshotRepository = sourceSnapshotRepository;
        this.engagementRepository = engagementRepository;
        this.engagementAppService = engagementAppService;
        this.taskItemRepository = taskItemRepository;
        this.contentStore = contentStore;
        this.linkSigner = linkSigner;
        this.clock = clock;
        this.maxBytes = maxBytes;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    @Transactional
    public SourceSnapshotModel upload(UUID engagementId, UUID clientId, String filename, String contentType,
                                      byte[] content) {
        EngagementModel engagement = engagementAppService.getForClient(engagementId, clientId);
        engagement.requireActive();
        SourceSnapshotModel snapshot = SourceSnapshotModel.upload(engagementId, clientId, filename, contentType,
                content, maxBytes, clock.instant());
        // Bytes first, then the record that references them: both in this transaction, under the store's
        // per-hash lock, so a concurrent retention pass of the same bytes waits and then sees this record.
        contentStore.put(snapshot.sha256(), content);
        sourceSnapshotRepository.insert(snapshot);
        log.info("SourceSnapshot {} uploaded under engagement {} ({} bytes, {})", snapshot.id(), engagementId,
                snapshot.sizeBytes(), snapshot.mediaType());
        return snapshot;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceView> listForEngagement(UUID engagementId, UUID clientId) {
        engagementAppService.getForClient(engagementId, clientId);
        List<SourceSnapshotModel> snapshots = sourceSnapshotRepository.findByEngagementId(engagementId);
        var downloads = sourceSnapshotRepository.downloadsOf(snapshots.stream().map(SourceSnapshotModel::id).toList());
        return snapshots.stream()
                .map(s -> new SourceView(s, downloads.getOrDefault(s.id(), List.of())))
                .toList();
    }

    @Override
    public Map<UUID, SourceSnapshotModel> attachForBooking(EngagementModel engagement, Collection<UUID> sourceIds) {
        Map<UUID, SourceSnapshotModel> locked = sourceSnapshotRepository.lockByIds(sourceIds);
        for (UUID id : sourceIds) {
            SourceSnapshotModel snapshot = locked.get(id);
            if (snapshot == null || !snapshot.engagementId().equals(engagement.id())
                    || !snapshot.clientId().equals(engagement.clientId())) {
                throw new DomainException(ResultCode.VALIDATION_ERROR,
                        "items: document " + id + " was not uploaded under this Engagement");
            }
            snapshot.requireContent();
        }
        return locked;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DispatchItem> withSignedLinks(List<DispatchItem> items, Instant executionDeadline) {
        if (items.stream().noneMatch(i -> i.sourceSnapshotId() != null)) {
            return items;
        }
        // The link's expiry has whole-second precision; the WorkOrder states the same instant.
        Instant expiresAt = Instant.ofEpochSecond(executionDeadline.getEpochSecond());
        return items.stream().map(item -> {
            if (item.sourceSnapshotId() == null) {
                return item;
            }
            SourceSnapshotModel s = sourceSnapshotRepository.findById(item.sourceSnapshotId())
                    .orElseThrow(() -> new IllegalStateException("Item " + item.itemNo() + " names a missing source"));
            String url = publicBaseUrl + "/api/source-links/" + linkSigner.sign(item.itemId(), expiresAt);
            return item.withSource(new DispatchSource(url, s.sha256(), s.mediaType(), s.sizeBytes(), s.filename(),
                    expiresAt));
        }).toList();
    }

    @Override
    @Transactional
    public Download download(String token, String remoteAddress, String userAgent) {
        Optional<SourceLinkSigner.Claims> claims = linkSigner.verify(token);
        Optional<TaskItemModel> item = claims.flatMap(c -> taskItemRepository.findById(c.itemId()))
                .filter(i -> i.sourceSnapshotId() != null);
        if (item.isEmpty()) {
            return new Download.Refused(Download.Reason.INVALID);
        }
        TaskItemModel it = item.get();
        Instant now = clock.instant();
        SourceSnapshotModel snapshot = sourceSnapshotRepository.findById(it.sourceSnapshotId()).orElseThrow();
        Optional<byte[]> content = snapshot.isRetired() ? Optional.empty() : contentStore.get(snapshot.sha256());
        SourceDownloadOutcome outcome;
        if (now.isAfter(claims.get().expiresAt())) {
            outcome = SourceDownloadOutcome.EXPIRED;
        } else if (content.isEmpty()) {
            outcome = SourceDownloadOutcome.RETIRED;
        } else {
            outcome = SourceDownloadOutcome.SERVED;
        }
        sourceSnapshotRepository.recordDownload(SourceDownloadModel.record(snapshot.id(), it.taskId(), it.id(),
                it.itemNo(), outcome, remoteAddress, userAgent, now));
        log.info("Source link for task {} item {}: {}", it.taskId(), it.itemNo(), outcome);
        return switch (outcome) {
            case SERVED -> new Download.Served(snapshot, content.get());
            case EXPIRED -> new Download.Refused(Download.Reason.EXPIRED);
            case RETIRED -> new Download.Refused(Download.Reason.RETIRED);
        };
    }

    @Override
    @Transactional(readOnly = true)
    public Download contentOf(UUID sourceSnapshotId) {
        SourceSnapshotModel snapshot = sourceSnapshotRepository.findById(sourceSnapshotId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Document not found"));
        Optional<byte[]> content = snapshot.isRetired() ? Optional.empty() : contentStore.get(snapshot.sha256());
        return content.<Download>map(bytes -> new Download.Served(snapshot, bytes))
                .orElseGet(() -> new Download.Refused(Download.Reason.RETIRED));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> retentionCandidateIds() {
        return sourceSnapshotRepository.idsHoldingContent();
    }

    @Override
    @Transactional
    public boolean applyRetention(UUID sourceSnapshotId) {
        // The row lock is the one a booking takes to attach the snapshot: after it, the Task list below
        // includes any booking that attached it meanwhile.
        SourceSnapshotModel snapshot = sourceSnapshotRepository.lockById(sourceSnapshotId).orElseThrow();
        if (snapshot.isRetired()) {
            return false;
        }
        EngagementModel engagement = engagementRepository.findById(snapshot.engagementId()).orElseThrow();
        // A window is open while any Task using it is not yet in a final status (TaskStatus#isClosed).
        boolean anyWindowOpen = taskItemRepository.taskStatusesUsingSource(sourceSnapshotId).stream()
                .anyMatch(status -> !status.isClosed());
        SourceSnapshotModel observed = snapshot.observeRetention(anyWindowOpen, clock.instant(),
                Duration.ofDays(engagement.retentionDays()));
        if (observed.equals(snapshot)) {
            return false;
        }
        sourceSnapshotRepository.updateRetention(observed);
        if (observed.isRetired()) {
            boolean deleted = contentStore.retire(observed.sha256());
            log.info("SourceSnapshot {} retired after {} days (content {})", observed.id(),
                    engagement.retentionDays(), deleted ? "deleted" : "still referenced elsewhere");
            return true;
        }
        return false;
    }
}
