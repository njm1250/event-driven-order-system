package com.ordersystem.partner.inbox;

import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.inbox.InboxRepository.Claim;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Renews the leases of this instance's running claims. It has its own thread: on the shared
 * scheduler a slow task (a blocked publish, a long query) could delay renewal past the lease and
 * hand running work to another instance.
 */
@Component
public class LeaseKeeper {
    private final InboxRepository inbox;
    private final PartnerSettings settings;
    private final Tracer tracer;
    private final Map<String, Claim> running = new ConcurrentHashMap<>();
    private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "lease-renewer");
        thread.setDaemon(true);
        return thread;
    });

    public LeaseKeeper(InboxRepository inbox, PartnerSettings settings, Tracer tracer) {
        this.inbox = inbox;
        this.settings = settings;
        this.tracer = tracer;
        if (settings.mode() == ProcessingMode.INBOX) {
            long every = settings.ownership().renewMs();
            renewer.scheduleWithFixedDelay(this::renew, every, every, TimeUnit.MILLISECONDS);
        }
    }

    public void track(Claim claim) {
        running.put(claim.eventId(), claim);
    }

    public void untrack(Claim claim) {
        running.remove(claim.eventId(), claim);
    }

    public int running() {
        return running.size();
    }

    void renew() {
        if (running.isEmpty()) return;
        try {
            List<Claim> lost = inbox.renew(List.copyOf(running.values()), settings.ownership().leaseMs());
            for (var claim : lost) {
                // The call may still be in flight; its result will be refused when it tries to record.
                running.remove(claim.eventId(), claim);
                tracer.trace("lease_lost", null, "eventId", claim.eventId(), "generation", claim.generation());
            }
        } catch (Exception e) {
            tracer.trace("lease_renew_error", null, "error", e.toString());
        }
    }

    @PreDestroy
    void close() {
        renewer.shutdownNow();
    }
}
