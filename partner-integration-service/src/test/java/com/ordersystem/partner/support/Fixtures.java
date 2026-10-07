package com.ordersystem.partner.support;

import com.ordersystem.common.events.PartnerOrderEvent;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;

public final class Fixtures {
    private Fixtures() { }

    public static PartnerSettings settings(ProcessingMode mode, String partnerUrl) {
        return new PartnerSettings(mode, "partner-test", partnerUrl, "test", false,
                4, 2, 2, 1000, 300, 200, 2000, 1, 200, 2000, (short) 1, 60000,
                new PartnerSettings.Breaker(300, 50, 50, 5, 3, 1000));
    }

    public static PartnerOrderEvent event(String seller, long orderId, int sequence) {
        String operation = switch (sequence) {
            case 1 -> "CREATE";
            case 2 -> "CHANGE";
            default -> "CANCEL";
        };
        return new PartnerOrderEvent(seller + "-" + orderId + "-" + sequence, "run", seller, orderId, sequence,
                operation, System.currentTimeMillis(), 1, sequence + 1, 100.0 + sequence);
    }

    public static PartnerTask task(String seller, long orderId, int sequence) {
        return new PartnerTask(event(seller, orderId, sequence), null, "partner-test", 0, sequence);
    }
}
