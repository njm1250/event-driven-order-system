package com.ordersystem.partner.processing;

import com.ordersystem.partner.config.PartnerSettings;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * One breaker per seller. Slow calls count like failures, so a seller that answers correctly but
 * slowly is cut off without shortening the HTTP timeout below its real latency.
 */
@Component
public class SellerCircuitBreakers {
    private final CircuitBreakerRegistry registry;

    public SellerCircuitBreakers(PartnerSettings settings) {
        var breaker = settings.breaker();
        var config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(breaker.windowSize())
                .minimumNumberOfCalls(breaker.minimumCalls())
                .failureRateThreshold(breaker.failureRatePercent())
                .slowCallDurationThreshold(Duration.ofMillis(breaker.slowCallMs()))
                .slowCallRateThreshold(breaker.slowCallRatePercent())
                .waitDurationInOpenState(Duration.ofMillis(breaker.openMs()))
                .permittedNumberOfCallsInHalfOpenState(1)
                // The newer candidates skip open sellers without asking the breaker, so the breaker
                // has to move to half-open by itself after the wait.
                .automaticTransitionFromOpenToHalfOpenEnabled(settings.mode().usesBackoff())
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
    }

    public boolean tryAcquire(String sellerId) {
        return registry.circuitBreaker(sellerId).tryAcquirePermission();
    }

    /** Returns a permission taken with {@link #tryAcquire} that was not used for a call. */
    public void release(String sellerId) {
        registry.circuitBreaker(sellerId).releasePermission();
    }

    public void onSuccess(String sellerId, long durationNanos) {
        registry.circuitBreaker(sellerId).onSuccess(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void onError(String sellerId, long durationNanos, Throwable error) {
        registry.circuitBreaker(sellerId).onError(durationNanos, TimeUnit.NANOSECONDS, error);
    }

    public CircuitBreaker.State state(String sellerId) {
        return registry.circuitBreaker(sellerId).getState();
    }

    /** Sellers whose circuit is open right now. */
    public java.util.Set<String> openSellers() {
        java.util.Set<String> result = new java.util.HashSet<>();
        registry.getAllCircuitBreakers().forEach(b -> {
            if (b.getState() == CircuitBreaker.State.OPEN) result.add(b.getName());
        });
        return result;
    }

    public Map<String, String> states() {
        Map<String, String> result = new TreeMap<>();
        registry.getAllCircuitBreakers().forEach(b -> result.put(b.getName(), b.getState().name()));
        return result;
    }
}
