package com.hireai.controller.biz.engagement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService.SourceView;
import com.hireai.controller.biz.acceptance.converter.Acceptance2DTOConverter;
import com.hireai.controller.biz.acceptance.dto.ContractDTO;
import com.hireai.controller.biz.acceptance.dto.ContractRequest;
import com.hireai.domain.biz.acceptance.engagement.model.EngagementModel;
import com.hireai.domain.biz.acceptance.source.model.SourceDownloadModel;
import com.hireai.domain.biz.acceptance.source.model.SourceSnapshotModel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Web shapes of Engagements and their documents (spec S4, D44). */
public final class EngagementDTOs {

    private EngagementDTOs() {
    }

    /**
     * Opens an Engagement with an Agent you chose. {@code confidentialConsent} must be true: it records
     * your consent to send this Agent confidential documents. {@code templateVariant} (optional) is a
     * Contract the Agent must accept before the Engagement is active. {@code retentionDays}: how long the
     * documents are kept after the last window closes (1-30, default 30).
     */
    public record OpenEngagementRequest(@NotNull UUID agentId, boolean confidentialConsent,
                                        @Valid ContractRequest templateVariant, Integer retentionDays) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EngagementDTO(UUID id, UUID clientId, UUID agentId,
                                /** AWAITING_AGENT_ACCEPTANCE, ACTIVE or ENDED. */
                                String status, Instant consentedAt, ContractDTO templateVariant,
                                Instant agentAcceptedAt, int retentionDays, Instant createdAt, Instant endedAt) {

        public static EngagementDTO of(EngagementModel e) {
            return new EngagementDTO(e.id(), e.clientId(), e.agentId(), e.status().name(), e.consentedAt(),
                    Acceptance2DTOConverter.toDTO(e.templateVariant()), e.agentAcceptedAt(), e.retentionDays(),
                    e.createdAt(), e.endedAt());
        }
    }

    /**
     * A document uploaded under an Engagement. {@code contentDeletedAt} is set once retention deleted the
     * content (only the hash is kept). {@code downloads} is every attempt through an Item's signed link.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceDTO(UUID id, UUID engagementId, String filename, String mediaType, long sizeBytes,
                            String sha256, Instant uploadedAt, Instant retentionStartedAt, Instant contentDeletedAt,
                            List<DownloadDTO> downloads) {

        public static SourceDTO of(SourceSnapshotModel s, List<SourceDownloadModel> downloads) {
            return new SourceDTO(s.id(), s.engagementId(), s.filename(), s.mediaType(), s.sizeBytes(), s.sha256(),
                    s.uploadedAt(), s.retentionStartedAt(), s.contentDeletedAt(),
                    downloads == null ? null : downloads.stream().map(DownloadDTO::of).toList());
        }

        public static SourceDTO of(SourceView view) {
            return of(view.snapshot(), view.downloads());
        }
    }

    /** One download attempt: {@code outcome} SERVED, EXPIRED or RETIRED. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DownloadDTO(Instant at, UUID taskId, int itemNo, String outcome, String remoteAddress) {

        static DownloadDTO of(SourceDownloadModel d) {
            return new DownloadDTO(d.at(), d.taskId(), d.itemNo(), d.outcome().name(), d.remoteAddress());
        }
    }
}
