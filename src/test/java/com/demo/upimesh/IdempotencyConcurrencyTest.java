package com.demo.upimesh;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The key correctness test for the whole system.
 *
 * Three bridge nodes simultaneously deliver the SAME encrypted packet.
 * Without idempotency, the sender would be debited three times.
 * With it, exactly one settles and the other two are dropped.
 */
@SpringBootTest
public class IdempotencyConcurrencyTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridgeIngestionService;
    @Autowired private IdempotencyService idempotencyService;
    @Autowired private AccountRepository accountRepository;

    @BeforeEach
    void reset() {
        idempotencyService.clear();
        // Reset balances
        accountRepository.save(new Account("alice@demo", "Alice", new BigDecimal("5000.00")));
        accountRepository.save(new Account("bob@demo",   "Bob",   new BigDecimal("1000.00")));
        accountRepository.save(new Account("carol@demo", "Carol", new BigDecimal("2500.00")));
        accountRepository.save(new Account("dave@demo",  "Dave",  new BigDecimal("500.00")));
    }

    @Test
    void singlePacketDeliveredByThreeBridgesSettlesExactlyOnce() throws Exception {
        BigDecimal amount = new BigDecimal("200.00");
        MeshPacket packet = demoService.createPacket("alice@demo", "bob@demo", amount, "1234", 5);

        // Three bridges all have the exact same packet and upload simultaneously.
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch go    = new CountDownLatch(1);
        List<BridgeIngestionService.IngestResult> results = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (int i = 1; i <= 3; i++) {
            final String bridge = "bridge-" + i;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await(); // all threads wait for the gun
                    results.add(bridgeIngestionService.ingest(packet, bridge, i));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        ready.await();   // wait for all 3 threads to be ready
        go.countDown();  // fire!
        pool.shutdown();
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        long settled  = results.stream().filter(r -> "SETTLED".equals(r.outcome())).count();
        long dropped  = results.stream().filter(r -> "DUPLICATE_DROPPED".equals(r.outcome())).count();

        assertEquals(1, settled,  "Exactly one bridge should settle");
        assertEquals(2, dropped,  "The other two should be dropped as duplicates");

        // Alice debited exactly once
        BigDecimal aliceBalance = accountRepository.findById("alice@demo")
                .map(Account::getBalance).orElseThrow();
        assertEquals(new BigDecimal("4800.00"), aliceBalance,
                "Alice should be debited exactly ₹200, not ₹600");

        // Bob credited exactly once
        BigDecimal bobBalance = accountRepository.findById("bob@demo")
                .map(Account::getBalance).orElseThrow();
        assertEquals(new BigDecimal("1200.00"), bobBalance,
                "Bob should be credited exactly ₹200");
    }

    @Test
    void twoDistinctPacketsBothSettle() throws Exception {
        BigDecimal amount = new BigDecimal("100.00");
        MeshPacket p1 = demoService.createPacket("alice@demo", "bob@demo", amount, "1234", 5);
        MeshPacket p2 = demoService.createPacket("alice@demo", "bob@demo", amount, "1234", 5);

        BridgeIngestionService.IngestResult r1 = bridgeIngestionService.ingest(p1, "bridge-1", 2);
        BridgeIngestionService.IngestResult r2 = bridgeIngestionService.ingest(p2, "bridge-2", 3);

        assertEquals("SETTLED", r1.outcome(), "First distinct packet should settle");
        assertEquals("SETTLED", r2.outcome(), "Second distinct packet (different nonce) should also settle");

        BigDecimal aliceBalance = accountRepository.findById("alice@demo")
                .map(Account::getBalance).orElseThrow();
        assertEquals(new BigDecimal("4800.00"), aliceBalance,
                "Alice debited ₹200 total for two legitimate payments");
    }

    @Test
    void insufficientFundsIsRejected() throws Exception {
        // Dave only has ₹500 — try to send ₹1000
        MeshPacket packet = demoService.createPacket("dave@demo", "alice@demo",
                new BigDecimal("1000.00"), "1234", 5);

        BridgeIngestionService.IngestResult r = bridgeIngestionService.ingest(packet, "bridge-1", 1);

        // BUG FIX: outcome now correctly reports REJECTED for insufficient funds
        // (previously this test asserted the bug itself: "SETTLED" even though
        // no money moved — that mismatch was a symptom of root cause #2).
        assertEquals("REJECTED", r.outcome());
        BigDecimal daveBalance = accountRepository.findById("dave@demo")
                .map(Account::getBalance).orElseThrow();
        assertEquals(new BigDecimal("500.00"), daveBalance, "Dave's balance should be unchanged");
    }
}
