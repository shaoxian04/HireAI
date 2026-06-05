package com.hireai.support;

import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.UUID;

/**
 * Test-only fallback bean for the {@link DispatchTokenService} port. The real HMAC-backed
 * implementation lives in the dispatch track (Track B) and is NOT present in this isolated
 * worktree, so any full {@code @SpringBootTest} context that wires the new
 * {@code AgentCallbackAppServiceImpl} (which depends on the port) would otherwise fail to
 * start. This no-op stub satisfies the port so unrelated integration tests (Task/Wallet)
 * still boot. It is {@code @ConditionalOnMissingBean}, so a test that supplies its own
 * {@code @MockBean DispatchTokenService} (e.g. AgentCallbackIntegrationTest) overrides it.
 * Lives under {@code src/test} only — it never ships to production.
 */
@Configuration
public class TestDispatchTokenServiceConfig {

    @Bean
    @ConditionalOnMissingBean(DispatchTokenService.class)
    public DispatchTokenService testDispatchTokenService() {
        return new DispatchTokenService() {
            @Override
            public String issue(UUID taskId, UUID agentVersionId, Duration ttl) {
                throw new UnsupportedOperationException(
                        "No dispatch-token implementation in this worktree (Track B owns it)");
            }

            @Override
            public DispatchTokenClaims verify(String token) {
                throw new UnsupportedOperationException(
                        "No dispatch-token implementation in this worktree (Track B owns it)");
            }
        };
    }
}
