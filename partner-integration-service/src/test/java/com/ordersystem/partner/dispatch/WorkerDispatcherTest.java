package com.ordersystem.partner.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.inbox.InboxRepository;
import com.ordersystem.partner.inbox.LeaseKeeper;
import com.ordersystem.partner.processing.SellerCircuitBreakers;
import org.springframework.transaction.support.TransactionTemplate;
import com.ordersystem.partner.processing.PartnerOrderProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static com.ordersystem.partner.support.Fixtures.event;
import static com.ordersystem.partner.support.Fixtures.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

class WorkerDispatcherTest {
    private final AdmissionPolicy admission = new AdmissionPolicy(4, 2, 2, 1000, 100, System::currentTimeMillis);
    private final AsyncPendingQueue pending = new AsyncPendingQueue();

    private WorkerDispatcher dispatcher(ProcessingMode mode, PartnerOrderProcessor processor) {
        var settings = settings(mode, "http://unused");
        var tracer = new Tracer(new ObjectMapper(), settings);
        var inbox = mock(InboxRepository.class);
        return new WorkerDispatcher(settings, inbox, pending, admission, processor, new SellerCircuitBreakers(settings),
                new LeaseKeeper(inbox, settings, tracer), mock(TransactionTemplate.class), tracer);
    }

    private WorkerDispatcher dispatcher(ProcessingMode mode) {
        return dispatcher(mode, mock(PartnerOrderProcessor.class));
    }

    private PartnerTask received(Acknowledgment ack) {
        var task = new PartnerTask(event("normal", 1, 1), ack, "partner-test", 0, 0);
        pending.add(task);
        return task;
    }

    /** Regression: the async mode stopped acknowledging Kafka redeliveries of finished events. */
    @Test
    void asyncRedeliveryOfAFinishedEventStillRunsAndIsAcknowledged() {
        var dispatcher = dispatcher(ProcessingMode.ASYNC);
        var first = received(mock(Acknowledgment.class));
        admission.tryAdmit(first);
        dispatcher.run(first);

        var redeliveredAck = mock(Acknowledgment.class);
        var redelivered = received(redeliveredAck);
        assertThat(admission.tryAdmit(redelivered).admitted()).isTrue();
        dispatcher.run(redelivered);
        verify(redeliveredAck).acknowledge();
    }

    @Test
    void inboxDoesNotRunAFinishedEventAgainFromAStaleSnapshot() {
        var dispatcher = dispatcher(ProcessingMode.INBOX);
        var task = new PartnerTask(event("normal", 1, 1), null, "partner-test", 0, 0);
        admission.tryAdmitLocal(task);
        dispatcher.runClaimed(task, new InboxRepository.Claim(task.eventId(), "normal", "me", 1, 1, false, false));

        var staleCopy = new PartnerTask(event("normal", 1, 1), null, "partner-test", 0, 0);
        assertThat(admission.tryAdmitLocal(staleCopy).admitted()).isFalse();
    }

    @Test
    void stopLetsInFlightCallsFinishAndStartsNoNewWork() throws Exception {
        var processor = mock(PartnerOrderProcessor.class);
        doAnswer(call -> { Thread.sleep(300); return null; }).when(processor).process(any());
        var dispatcher = dispatcher(ProcessingMode.ASYNC, processor);
        var ack = mock(Acknowledgment.class);
        received(ack);
        dispatcher.dispatch();

        dispatcher.stop();

        verify(ack).acknowledge();
        assertThat(admission.active()).isZero();
        received(mock(Acknowledgment.class));
        dispatcher.dispatch();
        assertThat(admission.active()).isZero();
    }
}
