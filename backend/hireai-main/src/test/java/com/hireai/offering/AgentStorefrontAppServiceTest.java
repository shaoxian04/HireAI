package com.hireai.offering;

import com.hireai.application.biz.offering.agent.AgentReadAppService;
import com.hireai.application.biz.offering.agent.impl.AgentStorefrontAppServiceImpl;
import com.hireai.application.port.query.BuilderStatsQueryPort;
import com.hireai.application.port.storage.MediaStoragePort;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.storefront.info.ProfileUpdateInfo;
import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;
import com.hireai.domain.biz.offering.storefront.repository.StorefrontRepository;
import com.hireai.domain.biz.reputation.model.ReviewModel;
import com.hireai.domain.biz.reputation.repository.ReviewRepository;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AgentStorefrontAppServiceImpl using plain Mockito (no Spring context).
 * Every test verifies the ownership gate (getForOwner) is invoked before any mutation.
 */
class AgentStorefrontAppServiceTest {

    private final AgentReadAppService agentReadAppService = mock(AgentReadAppService.class);
    private final StorefrontRepository profileRepository = mock(StorefrontRepository.class);
    private final MediaStoragePort mediaStoragePort = mock(MediaStoragePort.class);
    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final BuilderStatsQueryPort builderStatsQueryPort = mock(BuilderStatsQueryPort.class);

    private final AgentStorefrontAppServiceImpl service = new AgentStorefrontAppServiceImpl(
            agentReadAppService, profileRepository, mediaStoragePort, reviewRepository,
            builderStatsQueryPort);

    // ---- helpers ----

    private StorefrontModel defaultProfile(UUID agentId) {
        return StorefrontModel.createDefault(agentId);
    }

    private ReviewModel reviewFor(UUID agentId) {
        return new ReviewModel(UUID.randomUUID(), null, UUID.randomUUID(), agentId,
                4, "Good agent", null, true, Instant.now());
    }

    // ---- updateProfile ----

    @Test
    void updateProfile_happyPath_ownerGateVerifiedAndSaved() {
        UUID agentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        StorefrontModel existing = defaultProfile(agentId);

        when(profileRepository.findByAgentId(agentId)).thenReturn(Optional.of(existing));
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProfileUpdateInfo info = new ProfileUpdateInfo("My tagline", "desc", "sample", true);
        StorefrontModel result = service.updateProfile(agentId, ownerId, info);

        // Ownership gate must fire
        verify(agentReadAppService).getForOwner(agentId, ownerId);

        // Saved model has correct tagline and listed flag
        ArgumentCaptor<StorefrontModel> captor = ArgumentCaptor.forClass(StorefrontModel.class);
        verify(profileRepository).save(captor.capture());
        StorefrontModel saved = captor.getValue();
        assertThat(saved.tagline()).isEqualTo("My tagline");
        assertThat(saved.listed()).isTrue();
        assertThat(result.tagline()).isEqualTo("My tagline");
    }

    @Test
    void updateProfile_foreignOwner_notFoundPropagatesAndRepoNeverTouched() {
        UUID agentId = UUID.randomUUID();
        UUID foreignOwner = UUID.randomUUID();

        when(agentReadAppService.getForOwner(agentId, foreignOwner))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId));

        ProfileUpdateInfo info = new ProfileUpdateInfo("x", null, null, false);
        assertThatThrownBy(() -> service.updateProfile(agentId, foreignOwner, info))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));

        verify(profileRepository, never()).save(any());
        verify(profileRepository, never()).findByAgentId(any());
    }

    // ---- uploadMedia ----

    @Test
    void uploadMedia_happyLogoJpeg_portCalledWithCorrectKeyAndProfileSaved() {
        UUID agentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] bytes = new byte[1024]; // 1 KB
        String returnedUrl = "https://storage.example.com/agents/" + agentId + "/logo.jpg";

        when(profileRepository.findByAgentId(agentId)).thenReturn(Optional.of(defaultProfile(agentId)));
        when(mediaStoragePort.upload(any(), any(), any())).thenReturn(returnedUrl);
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StorefrontModel result = service.uploadMedia(agentId, ownerId, "logo", "image/jpeg", 1024, bytes);

        verify(agentReadAppService).getForOwner(agentId, ownerId);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mediaStoragePort).upload(keyCaptor.capture(), any(), any());
        assertThat(keyCaptor.getValue())
                .matches("agents/" + agentId + "/logo-[0-9a-f\\-]+\\.jpg");

        assertThat(result.logoUrl()).isEqualTo(returnedUrl);
    }

    @Test
    void uploadMedia_unknownKind_validationErrorPortNeverCalled() {
        UUID agentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] bytes = new byte[512];

        assertThatThrownBy(() ->
                service.uploadMedia(agentId, ownerId, "banner", "image/png", 512, bytes))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));

        verify(mediaStoragePort, never()).upload(any(), any(), any());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void uploadMedia_pdfContentType_validationErrorPortNeverCalled() {
        UUID agentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] bytes = new byte[512];

        assertThatThrownBy(() ->
                service.uploadMedia(agentId, ownerId, "logo", "application/pdf", 512, bytes))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));

        verify(mediaStoragePort, never()).upload(any(), any(), any());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void uploadMedia_sizeOver2MB_validationErrorPortNeverCalled() {
        UUID agentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] bytes = new byte[1]; // bytes array size doesn't matter; sizeBytes param controls check
        long tooLarge = 3L * 1024 * 1024; // 3 MB

        assertThatThrownBy(() ->
                service.uploadMedia(agentId, ownerId, "logo", "image/png", tooLarge, bytes))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));

        verify(mediaStoragePort, never()).upload(any(), any(), any());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void uploadMedia_galleryFull_domainRuleViolationPortNeverCalled() {
        UUID agentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        byte[] bytes = new byte[512];

        // Build a profile already at MAX_GALLERY
        StorefrontModel fullProfile = defaultProfile(agentId);
        for (int i = 0; i < StorefrontModel.MAX_GALLERY; i++) {
            fullProfile = fullProfile.addGalleryUrl("https://img.example.com/" + i + ".png");
        }
        when(profileRepository.findByAgentId(agentId)).thenReturn(Optional.of(fullProfile));

        final StorefrontModel finalFull = fullProfile;
        assertThatThrownBy(() ->
                service.uploadMedia(agentId, ownerId, "gallery", "image/png", 512, bytes))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.DOMAIN_RULE_VIOLATION));

        verify(mediaStoragePort, never()).upload(any(), any(), any());
        verify(profileRepository, never()).save(any());
    }

    // ---- respondToReview ----

    @Test
    void respondToReview_happy_savedModelHasResponse() {
        UUID agentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ReviewModel review = reviewFor(agentId);

        when(reviewRepository.findById(review.id())).thenReturn(Optional.of(review));
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReviewModel result = service.respondToReview(agentId, ownerId, review.id(), "Thanks!");

        verify(agentReadAppService).getForOwner(agentId, ownerId);

        ArgumentCaptor<ReviewModel> captor = ArgumentCaptor.forClass(ReviewModel.class);
        verify(reviewRepository).save(captor.capture());
        assertThat(captor.getValue().builderResponse()).isEqualTo("Thanks!");
        assertThat(result.builderResponse()).isEqualTo("Thanks!");
    }

    @Test
    void respondToReview_reviewBelongsToDifferentAgent_notFoundSaveNeverCalled() {
        UUID agentId = UUID.randomUUID();
        UUID differentAgentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ReviewModel review = reviewFor(differentAgentId); // belongs to a different agent

        when(reviewRepository.findById(review.id())).thenReturn(Optional.of(review));

        assertThatThrownBy(() ->
                service.respondToReview(agentId, ownerId, review.id(), "Thanks!"))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));

        verify(reviewRepository, never()).save(any());
    }

    // ---- getStats ----

    @Test
    void getStats_happyPath_ownerGateVerifiedAndBundleAssembled() {
        UUID agentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        BuilderStatsQueryPort.StatsRow statsRow = new BuilderStatsQueryPort.StatsRow(
                4, 2, 1, 1,
                new BigDecimal("80.00"), new BigDecimal("40.00"),
                30.0, 1, 1);
        List<BuilderStatsQueryPort.TrendPointRow> trend =
                List.of(new BuilderStatsQueryPort.TrendPointRow(LocalDate.now(), 4));
        List<BuilderStatsQueryPort.RecentTaskRow> recent =
                List.of(new BuilderStatsQueryPort.RecentTaskRow(
                        UUID.randomUUID(), "Task A", "RESULT_RECEIVED", Instant.now()));

        when(builderStatsQueryPort.stats(agentId)).thenReturn(statsRow);
        when(builderStatsQueryPort.trend(agentId, 14)).thenReturn(trend);
        when(builderStatsQueryPort.recentTasks(agentId, 10)).thenReturn(recent);

        BuilderStatsQueryPort.StatsBundle bundle = service.getStats(agentId, ownerId);

        // Owner gate must fire BEFORE port calls
        verify(agentReadAppService).getForOwner(agentId, ownerId);
        verify(builderStatsQueryPort).stats(agentId);
        verify(builderStatsQueryPort).trend(agentId, 14);
        verify(builderStatsQueryPort).recentTasks(agentId, 10);

        assertThat(bundle.stats().total()).isEqualTo(4);
        assertThat(bundle.stats().completed()).isEqualTo(2);
        assertThat(bundle.stats().creditsInEscrow()).isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(bundle.trend()).hasSize(1);
        assertThat(bundle.recent()).hasSize(1);
    }

    @Test
    void getStats_foreignOwner_notFoundAndPortNeverCalled() {
        UUID agentId = UUID.randomUUID();
        UUID foreignOwner = UUID.randomUUID();

        when(agentReadAppService.getForOwner(agentId, foreignOwner))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + agentId));

        assertThatThrownBy(() -> service.getStats(agentId, foreignOwner))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));

        verify(builderStatsQueryPort, never()).stats(any());
        verify(builderStatsQueryPort, never()).trend(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(builderStatsQueryPort, never()).recentTasks(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
