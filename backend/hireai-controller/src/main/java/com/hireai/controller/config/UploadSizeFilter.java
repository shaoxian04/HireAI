package com.hireai.controller.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Keeps the large multipart limit to the one endpoint that needs it. The servlet multipart limit
 * ({@code spring.servlet.multipart.*}) is sized for an Engagement document ({@code POST
 * /api/engagements/{id}/sources}); every other multipart request (a storefront image) is refused with 413
 * here when it declares more than {@code hireai.upload.default-max-bytes}, before anything is buffered.
 * A request that declares no length is left to the servlet limit and the endpoint's own check.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadSizeFilter extends OncePerRequestFilter {

    private static final Pattern SOURCE_UPLOAD = Pattern.compile("^/api/engagements/[^/]+/sources/?$");

    private final long defaultMaxBytes;

    public UploadSizeFilter(@Value("${hireai.upload.default-max-bytes:2162688}") long defaultMaxBytes) {
        this.defaultMaxBytes = defaultMaxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return type == null || !type.toLowerCase().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE)
                || SOURCE_UPLOAD.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > defaultMaxBytes) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"success\":false,\"code\":\"VALIDATION_ERROR\",\"message\":\"Upload too large: "
                    + "at most " + defaultMaxBytes + " bytes here\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }
}
