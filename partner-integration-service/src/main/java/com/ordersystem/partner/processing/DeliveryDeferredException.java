package com.ordersystem.partner.processing;

/** The event cannot be delivered yet; the caller decides whether to wait, retry or park it. */
public class DeliveryDeferredException extends Exception {
    public enum Reason { PREDECESSOR_PENDING, CIRCUIT_OPEN, PARTNER_REJECTED }

    private final Reason reason;

    public DeliveryDeferredException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
