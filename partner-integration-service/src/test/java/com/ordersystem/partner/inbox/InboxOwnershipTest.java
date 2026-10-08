package com.ordersystem.partner.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.completion.CompletionOutbox;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.inbox.InboxRepository.Claim;
import com.ordersystem.partner.inbox.InboxRepository.ClaimResult;
import com.ordersystem.partner.processing.PartnerApiClient;
import com.ordersystem.partner.processing.PartnerOrderProcessor;
import com.ordersystem.partner.processing.PartnerOrderRepository;
import com.ordersystem.partner.processing.SellerCircuitBreakers;
import com.ordersystem.partner.support.MySqlTestBase;
import com.ordersystem.partner.support.StubPartnerApi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.ordersystem.partner.support.Fixtures.settings;
import static com.ordersystem.partner.support.Fixtures.task;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Claims shared by several instances through the database: one owner, seller permits, leases. */
class InboxOwnershipTest extends MySqlTestBase {
    private static final long LEASE = 15_000;
    private final ObjectMapper json = new ObjectMapper();
    private final InboxRepository inbox = new InboxRepository(db, json);

    private void store(PartnerTask task) throws Exception {
        String payload = json.writeValueAsString(task.event());
        tx.execute(s -> inbox.store(task, payload, 200, 2000, System.currentTimeMillis()));
    }

    private InboxRepository.ClaimAttempt claim(String eventId, String seller, String owner) {
        return tx.execute(s -> inbox.claim(eventId, seller, owner, 2, 2, 1000, LEASE));
    }

    private void expireLease(String eventId) {
        db.update("UPDATE inbox SET lease_until=1 WHERE event_id=?", eventId);
        db.update("UPDATE seller_permit SET lease_until=1 WHERE event_id=?", eventId);
    }

    @Test
    void onlyOneInstanceClaimsARow() throws Exception {
        store(task("normal", 1, 1));

        assertThat(claim("normal-1-1", "normal", "a").result()).isEqualTo(ClaimResult.CLAIMED);
        assertThat(claim("normal-1-1", "normal", "b").result()).isEqualTo(ClaimResult.TAKEN);
    }

    @Test
    void sellerPermitsCapClaimsAcrossInstances() throws Exception {
        for (int order = 1; order <= 3; order++) store(task("slow", order, 1));

        assertThat(claim("slow-1-1", "slow", "a").result()).isEqualTo(ClaimResult.CLAIMED);
        assertThat(claim("slow-2-1", "slow", "b").result()).isEqualTo(ClaimResult.CLAIMED);
        assertThat(claim("slow-3-1", "slow", "a").result()).isEqualTo(ClaimResult.SELLER_FULL);
        assertThat(inbox.findReady(System.currentTimeMillis(), 10, 2, List.of())).isEmpty();
    }

    @Test
    void expiredLeaseIsReclaimedWithANewGeneration() throws Exception {
        store(task("normal", 1, 1));
        Claim first = claim("normal-1-1", "normal", "a").claim();
        expireLease("normal-1-1");

        assertThat(inbox.findReady(System.currentTimeMillis(), 10, 2, List.of())).extracting(PartnerTask::eventId)
                .containsExactly("normal-1-1");
        Claim second = claim("normal-1-1", "normal", "b").claim();

        assertThat(second.generation()).isEqualTo(first.generation() + 1);
        assertThat(second.reclaimed()).isTrue();
        assertThat(second.retry()).isTrue();
    }

    @Test
    void staleOwnerCannotRecordTheDeliveryOrFreeTheNewOwnersPermit() throws Exception {
        try (var partner = new StubPartnerApi()) {
            var settings = settings(ProcessingMode.INBOX, partner.url());
            var tracer = new Tracer(json, settings);
            var processor = new PartnerOrderProcessor(new PartnerOrderRepository(db), inbox,
                    new PartnerApiClient(json, settings, tracer), new SellerCircuitBreakers(settings),
                    new CompletionOutbox(db, json, settings, tracer), tx, settings, tracer);
            PartnerTask task = task("normal", 1, 1);
            store(task);
            Claim stale = claim("normal-1-1", "normal", "a").claim();
            expireLease("normal-1-1");
            Claim current = claim("normal-1-1", "normal", "b").claim();

            assertThatThrownBy(() -> processor.process(task, stale, false))
                    .isInstanceOf(PartnerOrderProcessor.StaleClaimException.class);
            tx.executeWithoutResult(s -> inbox.release(stale));

            assertThat(db.queryForObject("SELECT COUNT(*) FROM partner_effect", Long.class)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM seller_permit WHERE owner='b'", Long.class)).isEqualTo(1);
            processor.process(task, current, false);
            assertThat(db.queryForObject("SELECT state FROM inbox WHERE event_id='normal-1-1'", String.class)).isEqualTo("DONE");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM seller_permit WHERE owner IS NOT NULL", Long.class)).isZero();
        }
    }

    @Test
    void expiredLeaseRefusesTheOwnerEvenBeforeAnyoneReclaims() throws Exception {
        store(task("normal", 1, 1));
        Claim claim = claim("normal-1-1", "normal", "a").claim();
        expireLease("normal-1-1");

        Boolean held = tx.execute(s -> inbox.holds(claim));
        assertThat(held).isFalse();
        assertThat(inbox.renew(List.of(claim), LEASE)).containsExactly(claim);
    }

    @Test
    void retryBudgetIsSharedByAllInstances() throws Exception {
        for (int order = 1; order <= 3; order++) {
            store(task("slow", order, 1));
            Claim claim = claim("slow-" + order + "-1", "slow", "a").claim();
            tx.execute(s -> inbox.giveBack(claim, 0));
        }

        assertThat(claim("slow-1-1", "slow", "a").result()).isEqualTo(ClaimResult.CLAIMED);
        assertThat(claim("slow-2-1", "slow", "b").result()).isEqualTo(ClaimResult.CLAIMED);
        db.update("UPDATE seller_permit SET owner=NULL, lease_until=0");
        assertThat(claim("slow-3-1", "slow", "a").result()).isEqualTo(ClaimResult.RETRY_BUDGET);
    }

    /** Regression: old rows of one seller filled the candidate list and hid every other seller. */
    @Test
    void oneSellersBacklogDoesNotHideOtherSellers() throws Exception {
        for (int order = 1; order <= 10; order++) store(task("slow", order, 1));
        store(task("normal", 11, 1));

        assertThat(inbox.findReady(System.currentTimeMillis(), 3, 2, List.of())).extracting(PartnerTask::eventId)
                .containsExactly("slow-1-1", "slow-2-1", "normal-11-1");
    }

    /** Regression: a replay after the completed row was purged waited forever as PENDING. */
    @Test
    void replayOfADeliveredEventAfterPurgeIsNotStoredAgain() throws Exception {
        try (var partner = new StubPartnerApi()) {
            var settings = settings(ProcessingMode.INBOX, partner.url());
            var tracer = new Tracer(json, settings);
            var processor = new PartnerOrderProcessor(new PartnerOrderRepository(db), inbox,
                    new PartnerApiClient(json, settings, tracer), new SellerCircuitBreakers(settings),
                    new CompletionOutbox(db, json, settings, tracer), tx, settings, tracer);
            PartnerTask task = task("normal", 1, 1);
            store(task);
            processor.process(task, claim("normal-1-1", "normal", "a").claim(), false);
            inbox.deleteDoneBefore(Long.MAX_VALUE, 100);

            store(task("normal", 1, 1));

            assertThat(db.queryForObject("SELECT COUNT(*) FROM inbox", Long.class)).isZero();
        }
    }
}
