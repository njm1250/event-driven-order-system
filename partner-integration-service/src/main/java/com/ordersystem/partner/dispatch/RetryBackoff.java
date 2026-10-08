package com.ordersystem.partner.dispatch;

import com.ordersystem.partner.config.PartnerSettings;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import java.util.zip.CRC32;

/**
 * Full-jitter exponential backoff: a uniform wait between 0 and min(max, initial * 2^(n-1)). The
 * random draw is a function of (seed, eventId, n) instead of a shared generator, so the waits do
 * not depend on which thread or instance happens to retry first.
 */
public final class RetryBackoff {
    private final long initialMs;
    private final long maxMs;
    private final long seed;

    public RetryBackoff(long initialMs, long maxMs, long seed) {
        this.initialMs = initialMs;
        this.maxMs = maxMs;
        this.seed = seed;
    }

    public static RetryBackoff of(PartnerSettings settings) {
        return new RetryBackoff(settings.retryDelayMs(), settings.retry().maxDelayMs(), settings.retry().seed());
    }

    /** @param failures how many times this event has been put back so far, starting at 1 */
    public long delayMs(String eventId, int failures) {
        int exponent = Math.max(0, Math.min(failures - 1, 30));
        long ceiling = Math.min(maxMs, initialMs << exponent);
        var crc = new CRC32();
        crc.update((seed + "/" + eventId + "/" + failures).getBytes(StandardCharsets.UTF_8));
        return new SplittableRandom(crc.getValue()).nextLong(ceiling + 1);
    }
}
