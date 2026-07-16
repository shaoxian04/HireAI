package com.hireai.controller.config;

import com.hireai.application.biz.apikey.ApiKeyAuthService;
import com.hireai.application.biz.apikey.ApiKeyPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthenticationFilterTest {

    private final ApiKeyAuthService authService = mock(ApiKeyAuthService.class);
    private final ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(authService);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void setsPrincipalAndApiKeyContextForValidKeyViaAuthorizationHeader() throws Exception {
        UUID user = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        when(authService.authenticate("hk_live_secret")).thenReturn(Optional.of(
                new ApiKeyPrincipal(user, keyId, new BigDecimal("100.00"), null)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "ApiKey hk_live_secret");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(user);
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_API_CLIENT");
        assertThat(auth.getDetails()).isInstanceOf(ApiKeyContext.class);
        assertThat(((ApiKeyContext) auth.getDetails()).keyId()).isEqualTo(keyId);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void readsXApiKeyHeaderToo() throws Exception {
        UUID user = UUID.randomUUID();
        when(authService.authenticate("hk_live_x")).thenReturn(Optional.of(
                new ApiKeyPrincipal(user, UUID.randomUUID(), null, null)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "hk_live_x");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(user);
    }

    @Test
    void leavesContextEmptyForInvalidKeyOrNoHeader() throws Exception {
        when(authService.authenticate(any())).thenReturn(Optional.empty());
        MockHttpServletRequest bad = new MockHttpServletRequest();
        bad.addHeader("Authorization", "ApiKey nope");
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(bad, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doesNotOverrideAnExistingJwtAuthentication() throws Exception {
        // A Bearer JWT filter ran first and set an authentication; the ApiKey filter must not clobber it.
        UUID jwtUser = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        jwtUser, null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer some.jwt.token"); // not an ApiKey scheme
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(jwtUser);
    }
}
