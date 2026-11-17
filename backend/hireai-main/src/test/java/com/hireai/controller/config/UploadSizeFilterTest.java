package com.hireai.controller.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The 25 MB multipart limit is for Engagement documents only; other uploads keep a small one (#104). */
class UploadSizeFilterTest {

    private static final long SMALL = 2_162_688;
    private final UploadSizeFilter filter = new UploadSizeFilter(SMALL);

    @Test
    void aLargeStorefrontImageIsRefusedBeforeItIsRead() throws Exception {
        MockHttpServletResponse response = run("/api/agents/a-1/media", SMALL + 1);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("VALIDATION_ERROR");
    }

    @Test
    void anEngagementDocumentMayUseTheLargeLimit() throws Exception {
        assertThat(run("/api/engagements/e-1/sources", 20_000_000).getStatus()).isEqualTo(200);
    }

    @Test
    void aSmallUploadAndANonMultipartRequestPass() throws Exception {
        assertThat(run("/api/agents/a-1/media", 1000).getStatus()).isEqualTo(200);
        MockHttpServletRequest json = new MockHttpServletRequest("POST", "/api/tasks");
        json.setContentType("application/json");
        json.setContent(new byte[(int) SMALL + 10]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(json, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse run(String uri, long length) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public long getContentLengthLong() {
                return length;
            }
        };
        request.setContentType("multipart/form-data; boundary=x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
