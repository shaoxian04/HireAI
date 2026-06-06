package com.hireai.agent;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for AgentProfileModel — verifies creation defaults, content update, length
 * limits, gallery cap enforcement, and media removal. No Spring context needed.
 */
class AgentProfileModelTest {

    @Test
    void createDefaultIsUnlistedAndEmpty() {
        UUID agentId = UUID.randomUUID();
        AgentProfileModel profile = AgentProfileModel.createDefault(agentId);

        assertThat(profile.agentId()).isEqualTo(agentId);
        assertThat(profile.listed()).isFalse();
        assertThat(profile.featured()).isFalse();
        assertThat(profile.galleryUrls()).isEmpty();
    }

    @Test
    void createDefaultRejectsNullAgentId() {
        assertThatThrownBy(() -> AgentProfileModel.createDefault(null))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));
    }

    @Test
    void updateContentTrimsAndSetsListing() {
        UUID agentId = UUID.randomUUID();
        AgentProfileModel profile = AgentProfileModel.createDefault(agentId)
                .updateContent(" Fast summaries ", "Does X", "{\"sample\":1}", true);

        assertThat(profile.tagline()).isEqualTo("Fast summaries");
        assertThat(profile.listed()).isTrue();
    }

    @Test
    void taglineOverLimitRejected() {
        UUID agentId = UUID.randomUUID();
        String tooLong = "x".repeat(AgentProfileModel.MAX_TAGLINE + 1);

        assertThatThrownBy(() ->
                AgentProfileModel.createDefault(agentId).updateContent(tooLong, null, null, false))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));
    }

    @Test
    void galleryCapEnforced() {
        UUID agentId = UUID.randomUUID();
        AgentProfileModel profile = AgentProfileModel.createDefault(agentId);

        // Adding up to MAX_GALLERY should be fine
        for (int i = 0; i < AgentProfileModel.MAX_GALLERY; i++) {
            profile = profile.addGalleryUrl("https://img.example.com/" + i + ".png");
        }

        final AgentProfileModel full = profile;
        assertThatThrownBy(() -> full.addGalleryUrl("https://img.example.com/over.png"))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.DOMAIN_RULE_VIOLATION));
    }

    @Test
    void removeGalleryUrlRemovesExactMatch() {
        UUID agentId = UUID.randomUUID();
        AgentProfileModel profile = AgentProfileModel.createDefault(agentId)
                .addGalleryUrl("a.png")
                .addGalleryUrl("b.png");

        profile = profile.removeMedia("gallery", "a.png");

        assertThat(profile.galleryUrls()).containsExactly("b.png");
    }
}
