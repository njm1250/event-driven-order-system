package com.ordersystem.partner;

import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.dispatch.AdmissionPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers partner order events (create, change, cancel) to each seller's API in order per order,
 * while keeping one slow seller from delaying the others. See {@link com.ordersystem.partner.config.ProcessingMode}.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PartnerSettings.class)
public class PartnerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PartnerApplication.class, args);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    @Bean
    AdmissionPolicy admissionPolicy(PartnerSettings settings) {
        return new AdmissionPolicy(settings.workers(), settings.sellerConcurrency(), settings.retryBudget(),
                settings.retryWindowMs(), settings.retainedLimit(), System::currentTimeMillis);
    }
}
