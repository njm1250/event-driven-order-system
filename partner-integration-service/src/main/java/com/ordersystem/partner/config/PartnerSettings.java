package com.ordersystem.partner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All limits of one partner JVM. The numbers are shared by every mode so comparisons use the
 * same thread, connection and retry budgets.
 */
@ConfigurationProperties(prefix = "app")
public record PartnerSettings(
        ProcessingMode mode,
        String topic,
        String partnerUrl,
        @DefaultValue("unknown") String codeVersion,
        @DefaultValue("true") boolean traceEnabled,
        int workers,
        int sellerConcurrency,
        int retryBudget,
        long retryWindowMs,
        long retryDelayMs,
        int backlogLimit,
        int retainedLimit,
        @DefaultValue("1") int partitions,
        @DefaultValue("200") int inputBudget,
        @DefaultValue("5000") long callTimeoutMs,
        @DefaultValue Breaker breaker) {

    public String retryTopic() {
        return topic + "-retry";
    }

    /** Opens when at least half of the last calls were slow or failed; probes once per second. */
    public record Breaker(
            @DefaultValue("300") long slowCallMs,
            @DefaultValue("50") float slowCallRatePercent,
            @DefaultValue("50") float failureRatePercent,
            @DefaultValue("5") int windowSize,
            @DefaultValue("3") int minimumCalls,
            @DefaultValue("1000") long openMs) {
    }
}
