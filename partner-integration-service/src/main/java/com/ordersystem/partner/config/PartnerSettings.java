package com.ordersystem.partner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("60000") long inboxDoneRetentionMs,
        @DefaultValue("true") boolean inboxBatchIngest,
        @DefaultValue("10000") long shutdownDrainMs,
        @DefaultValue Breaker breaker,
        @DefaultValue Retry retry,
        @DefaultValue Ownership ownership,
        @DefaultValue("") String completionTopic,
        @DefaultValue("") String traceStages,
        @DefaultValue("0") int parallelLoadFactor) {

    public String retryTopic() {
        return topic + "-retry";
    }

    /** Empty means every stage is traced. */
    public Set<String> tracedStages() {
        return traceStages == null || traceStages.isBlank() ? Set.of()
                : Arrays.stream(traceStages.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
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

    /**
     * Exponential backoff with full jitter for the comparison candidates. The jitter is derived from
     * the seed, the event and the attempt number, so a rerun waits the same times in any order.
     */
    public record Retry(
            @DefaultValue("5000") long maxDelayMs,
            @DefaultValue("1251") long seed,
            @DefaultValue("4") int topicPartitions) {
    }

    /** Inbox work ownership across instances: a claim is a lease that its owner keeps renewing. */
    public record Ownership(
            @DefaultValue("15000") long leaseMs,
            @DefaultValue("3000") long renewMs) {
    }
}
