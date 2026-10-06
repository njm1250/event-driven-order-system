package com.ordersystem.partner.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.inbox.InboxRepository;
import com.ordersystem.partner.support.MySqlTestBase;
import com.ordersystem.partner.support.StubPartnerApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.ordersystem.partner.support.Fixtures.settings;
import static com.ordersystem.partner.support.Fixtures.task;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartnerOrderProcessorTest extends MySqlTestBase {
    private final ObjectMapper json = new ObjectMapper();
    private StubPartnerApi partner;

    @BeforeEach
    void startPartner() throws Exception {
        partner = new StubPartnerApi();
    }

    @AfterEach
    void stopPartner() {
        partner.close();
    }

    private PartnerOrderProcessor processor(ProcessingMode mode) {
        PartnerSettings settings = settings(mode, partner.url());
        var tracer = new Tracer(json, settings);
        return new PartnerOrderProcessor(new PartnerOrderRepository(db), new InboxRepository(db, json),
                new PartnerApiClient(json, settings, tracer), new SellerCircuitBreakers(settings), tx, settings, tracer);
    }

    @Test
    void deliversOperationsOfOneOrderInSequence() throws Exception {
        var processor = processor(ProcessingMode.SEQUENTIAL);
        for (int seq = 1; seq <= 3; seq++) processor.process(task("normal", 1, seq));

        assertThat(partner.requests()).containsExactly("normal-1-1", "normal-1-2", "normal-1-3");
        assertThat(db.queryForObject("SELECT operation FROM partner_order WHERE order_id=1", String.class)).isEqualTo("CANCEL");
    }

    @Test
    void changeBeforeCreateIsDeferredWithoutCallingThePartner() {
        var processor = processor(ProcessingMode.SEQUENTIAL);

        assertThatThrownBy(() -> processor.process(task("normal", 1, 2)))
                .isInstanceOfSatisfying(DeliveryDeferredException.class,
                        e -> assertThat(e.reason()).isEqualTo(DeliveryDeferredException.Reason.PREDECESSOR_PENDING));
        assertThat(partner.requests()).isEmpty();
    }

    @Test
    void redeliveredEventIsNotSentAgain() throws Exception {
        var processor = processor(ProcessingMode.SEQUENTIAL);
        processor.process(task("normal", 1, 1));
        processor.process(task("normal", 1, 1));

        assertThat(partner.requests()).hasSize(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM partner_effect", Long.class)).isEqualTo(1);
    }

    @Test
    void slowSellerOpensOnlyItsOwnCircuit() throws Exception {
        var processor = processor(ProcessingMode.RETRY_TOPIC);
        partner.delay("slow", 400);
        for (int order = 1; order <= 3; order++) processor.process(task("slow", order, 1));

        assertThatThrownBy(() -> processor.process(task("slow", 4, 1)))
                .isInstanceOfSatisfying(DeliveryDeferredException.class,
                        e -> assertThat(e.reason()).isEqualTo(DeliveryDeferredException.Reason.CIRCUIT_OPEN));
        processor.process(task("normal", 5, 1));
        assertThat(partner.requests()).containsExactly("slow-1-1", "slow-2-1", "slow-3-1", "normal-5-1");
    }

    @Test
    void inboxRowIsMarkedDoneInTheSameTransaction() throws Exception {
        var inbox = new InboxRepository(db, json);
        PartnerTask task = task("normal", 1, 1);
        tx.executeWithoutResult(s -> inbox.store(task, "{}", 200, 2000, 1));

        processor(ProcessingMode.INBOX).process(task);

        assertThat(db.queryForObject("SELECT state FROM inbox WHERE event_id=?", String.class, task.eventId())).isEqualTo("DONE");
    }
}
