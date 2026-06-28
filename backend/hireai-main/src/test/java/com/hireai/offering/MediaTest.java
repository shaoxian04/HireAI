package com.hireai.offering;

import com.hireai.domain.biz.offering.storefront.model.Media;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaTest {

    @Test
    void galleryCapEnforcedByTheVo() {
        Media media = Media.empty();
        for (int i = 0; i < Media.MAX_GALLERY; i++) {
            media = media.addGalleryUrl("https://img.example.com/" + i + ".png");
        }
        final Media full = media;
        assertThatThrownBy(() -> full.addGalleryUrl("https://img.example.com/over.png"))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.DOMAIN_RULE_VIOLATION));
    }

    @Test
    void removeGalleryAndLogo() {
        Media media = Media.empty()
                .withLogo("https://x/l.png")
                .addGalleryUrl("a.png")
                .addGalleryUrl("b.png");
        media = media.remove("gallery", "a.png").remove("logo", null);
        assertThat(media.galleryUrls()).containsExactly("b.png");
        assertThat(media.logoUrl()).isNull();
    }
}
