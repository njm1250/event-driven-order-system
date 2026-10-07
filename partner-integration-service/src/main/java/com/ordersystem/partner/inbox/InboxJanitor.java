package com.ordersystem.partner.inbox;

import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes completed inbox rows after a retention period. Without it the retained-row limit is
 * eventually reached and intake stops even though nothing is pending.
 */
@Component
public class InboxJanitor {
    private static final int BATCH = 1000;

    private final InboxRepository inbox;
    private final PartnerSettings settings;
    private final Tracer tracer;

    public InboxJanitor(InboxRepository inbox, PartnerSettings settings, Tracer tracer) {
        this.inbox = inbox;
        this.settings = settings;
        this.tracer = tracer;
    }

    @Scheduled(fixedDelay = 1000)
    public void purgeCompleted() {
        if (settings.mode() != ProcessingMode.INBOX) return;
        try {
            int deleted = inbox.deleteDoneBefore(System.currentTimeMillis() - settings.inboxDoneRetentionMs(), BATCH);
            if (deleted > 0) tracer.trace("inbox_purged", null, "rows", deleted);
        } catch (Exception e) {
            tracer.trace("inbox_purge_error", null, "error", e.toString());
        }
    }
}
