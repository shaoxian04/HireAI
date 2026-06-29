package com.hireai.domain.biz.offering.storefront.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.utility.exception.DomainException;

import java.util.ArrayList;
import java.util.List;

/**
 * Media value object for a {@link StorefrontModel}: the agent's logo, cover, and gallery image
 * URLs. Immutable — every change returns a new Media. Owns the gallery-capacity rule (the single
 * home for MAX_GALLERY), so callers (e.g. the media-upload flow) can fail fast before an upload.
 */
public record Media(String logoUrl, String coverUrl, List<String> galleryUrls) {

    public static final int MAX_GALLERY = 6;

    public Media {
        galleryUrls = galleryUrls == null ? List.of() : List.copyOf(galleryUrls);
    }

    public static Media empty() {
        return new Media(null, null, List.of());
    }

    public Media withLogo(String url) {
        return new Media(url, coverUrl, galleryUrls);
    }

    public Media withCover(String url) {
        return new Media(logoUrl, url, galleryUrls);
    }

    public void assertCanAddGallery() {
        if (galleryUrls.size() >= MAX_GALLERY) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Gallery is full (max " + MAX_GALLERY + " images)");
        }
    }

    public Media addGalleryUrl(String url) {
        assertCanAddGallery();
        List<String> next = new ArrayList<>(galleryUrls);
        next.add(url);
        return new Media(logoUrl, coverUrl, next);
    }

    /** Removes a media entry; kind is logo|cover|gallery. Unknown gallery URL is a no-op. */
    public Media remove(String kind, String url) {
        return switch (kind) {
            case "logo" -> withLogo(null);
            case "cover" -> withCover(null);
            case "gallery" -> {
                List<String> next = new ArrayList<>(galleryUrls);
                next.remove(url);
                yield new Media(logoUrl, coverUrl, next);
            }
            default -> throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Unknown media kind: " + kind);
        };
    }
}
