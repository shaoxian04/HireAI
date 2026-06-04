package com.hireai.application.config;

import com.hireai.domain.biz.task.service.TaskSubmitDomainService;
import com.hireai.domain.biz.wallet.service.WalletFreezeDomainService;
import com.hireai.domain.biz.wallet.service.WalletTopUpDomainService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers framework-free domain services as Spring beans. Domain services
 * carry no Spring annotations (the domain layer has zero framework imports), so
 * they are wired here in the application layer instead.
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public WalletTopUpDomainService walletTopUpDomainService() {
        return new WalletTopUpDomainService();
    }

    @Bean
    public WalletFreezeDomainService walletFreezeDomainService() {
        return new WalletFreezeDomainService();
    }

    @Bean
    public TaskSubmitDomainService taskSubmitDomainService() {
        return new TaskSubmitDomainService();
    }
}
