package com.hireai.application.config;

import com.hireai.domain.biz.offering.agent.service.AgentActivateDomainService;
import com.hireai.domain.biz.offering.agent.service.AgentDeactivateDomainService;
import com.hireai.domain.biz.offering.agent.service.AgentReactivateDomainService;
import com.hireai.domain.biz.offering.agent.service.AgentRegisterDomainService;
import com.hireai.domain.biz.offering.agent.service.AgentSuspendDomainService;
import com.hireai.domain.biz.offering.agent.service.impl.AgentActivateDomainServiceImpl;
import com.hireai.domain.biz.offering.agent.service.impl.AgentDeactivateDomainServiceImpl;
import com.hireai.domain.biz.offering.agent.service.impl.AgentReactivateDomainServiceImpl;
import com.hireai.domain.biz.offering.agent.service.impl.AgentRegisterDomainServiceImpl;
import com.hireai.domain.biz.offering.agent.service.impl.AgentSuspendDomainServiceImpl;
import com.hireai.domain.biz.task.routing.service.MatchingPolicy;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;
import com.hireai.domain.biz.task.routing.service.impl.RoutingMatchDomainServiceImpl;
import com.hireai.domain.biz.adjudication.service.SchemaValidator;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.adjudication.service.impl.ValidationDomainServiceImpl;
import com.hireai.domain.biz.identity.service.OAuthAccountLinkingDomainService;
import com.hireai.domain.biz.identity.service.impl.OAuthAccountLinkingDomainServiceImpl;
import com.hireai.domain.biz.task.service.TaskSubmitDomainService;
import com.hireai.domain.biz.task.service.impl.TaskSubmitDomainServiceImpl;
import com.hireai.domain.biz.ledger.settlement.service.SettlementDomainService;
import com.hireai.domain.biz.ledger.wallet.service.WalletFreezeDomainService;
import com.hireai.domain.biz.ledger.wallet.service.WalletTopUpDomainService;
import com.hireai.domain.biz.ledger.settlement.service.impl.SettlementDomainServiceImpl;
import com.hireai.domain.biz.ledger.wallet.service.impl.WalletFreezeDomainServiceImpl;
import com.hireai.domain.biz.ledger.wallet.service.impl.WalletTopUpDomainServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;

/**
 * Registers framework-free domain services as Spring beans. Domain services carry no
 * Spring annotations (the domain layer has zero framework imports), so the application
 * layer wires their implementations here, exposing them by their domain interfaces.
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public WalletTopUpDomainService walletTopUpDomainService() {
        return new WalletTopUpDomainServiceImpl();
    }

    @Bean
    public WalletFreezeDomainService walletFreezeDomainService() {
        return new WalletFreezeDomainServiceImpl();
    }

    @Bean
    public TaskSubmitDomainService taskSubmitDomainService() {
        return new TaskSubmitDomainServiceImpl();
    }

    @Bean
    public MatchingPolicy matchingPolicy(
            @Value("${hireai.matching.weight-reputation:0.40}") double weightReputation,
            @Value("${hireai.matching.weight-value:0.20}") double weightValue,
            @Value("${hireai.matching.weight-load:0.20}") double weightLoad,
            @Value("${hireai.matching.weight-exploration:0.20}") double weightExploration,
            @Value("${hireai.matching.epsilon:0.10}") double epsilon) {
        // MatchingPolicy's compact constructor validates; bad config = startup crash (spec §7).
        return new MatchingPolicy(weightReputation, weightValue, weightLoad, weightExploration, epsilon);
    }

    @Bean
    public RoutingMatchDomainService routingMatchDomainService(MatchingPolicy matchingPolicy) {
        return new RoutingMatchDomainServiceImpl(matchingPolicy, new SecureRandom());
    }

    @Bean
    public AgentRegisterDomainService agentRegisterDomainService() {
        return new AgentRegisterDomainServiceImpl();
    }

    @Bean
    public AgentActivateDomainService agentActivateDomainService() {
        return new AgentActivateDomainServiceImpl();
    }

    @Bean
    public AgentSuspendDomainService agentSuspendDomainService() {
        return new AgentSuspendDomainServiceImpl();
    }

    @Bean
    public AgentReactivateDomainService agentReactivateDomainService() {
        return new AgentReactivateDomainServiceImpl();
    }

    @Bean
    public AgentDeactivateDomainService agentDeactivateDomainService() {
        return new AgentDeactivateDomainServiceImpl();
    }

    @Bean
    public SettlementDomainService settlementDomainService() {
        return new SettlementDomainServiceImpl();
    }

    @Bean
    public OAuthAccountLinkingDomainService oAuthAccountLinkingDomainService() {
        return new OAuthAccountLinkingDomainServiceImpl();
    }

    @Bean
    public ValidationDomainService validationDomainService(SchemaValidator schemaValidator) {
        return new ValidationDomainServiceImpl(schemaValidator);
    }
}
