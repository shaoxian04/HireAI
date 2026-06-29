package com.hireai.controller.biz.agent.dto;

import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;

import java.util.List;

public record AgentProfileViewDTO(String tagline, String description, String sampleOutput,
                                  String logoUrl, String coverUrl, List<String> galleryUrls,
                                  boolean listed, boolean featured) {

    public static AgentProfileViewDTO from(StorefrontModel p) {
        return new AgentProfileViewDTO(p.tagline(), p.description(), p.sampleOutput(),
                p.logoUrl(), p.coverUrl(), p.galleryUrls(), p.listed(), p.featured());
    }
}
