package com.ordersystem.partner.kafka;

import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.dispatch.AdmissionPolicy;
import org.springframework.stereotype.Component;

/**
 * Admission for the retry-topic candidate, shared by the main and retry listeners. A listener
 * thread is intake, not an execution slot: it may wait briefly for a global slot, but a seller at
 * its limit or out of retry budget is parked on the retry topic instead of waited for, so one
 * seller cannot hold the main partition.
 */
@Component
public class RetryAdmission {
    private static final long MAX_WAIT_MS = 30_000;

    private final AdmissionPolicy admission;
    private final RetryLane retryLane;
    private final Tracer tracer;

    public RetryAdmission(AdmissionPolicy admission, RetryLane retryLane, Tracer tracer) {
        this.admission = admission;
        this.retryLane = retryLane;
        this.tracer = tracer;
    }

    /** @return true when admitted; the caller must {@link #release} it. False: the task was parked. */
    public boolean admitOrPark(PartnerTask task) throws Exception {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        while (true) {
            var decision = admission.tryAdmit(task);
            if (decision.admitted()) {
                if (decision.retry()) tracer.trace("retry_admitted", task, "admittedAt", decision.at());
                return true;
            }
            boolean sellerBound = decision.reason() == AdmissionPolicy.Reason.SELLER_FULL
                    || decision.reason() == AdmissionPolicy.Reason.RETRY_BUDGET;
            if (sellerBound || System.currentTimeMillis() > deadline) {
                retryLane.park(task, decision.reason().name());
                return false;
            }
            Thread.sleep(2);
        }
    }

    public void release(PartnerTask task) {
        admission.release(task);
    }
}
