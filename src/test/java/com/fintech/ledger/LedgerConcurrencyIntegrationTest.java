package com.fintech.ledger;

import com.fintech.ledger.domain.LedgerEntry;
import com.fintech.ledger.dto.AccountResponse;
import com.fintech.ledger.dto.CreateAccountRequest;
import com.fintech.ledger.dto.TransferRequest;
import com.fintech.ledger.dto.TransferResponse;
import com.fintech.ledger.exception.IdempotencyConflictException;
import com.fintech.ledger.exception.InsufficientFundsException;
import com.fintech.ledger.repository.LedgerEntryRepository;
import com.fintech.ledger.repository.OutboxEventRepository;
import com.fintech.ledger.service.AccountService;
import com.fintech.ledger.service.IdempotentTransferOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class LedgerConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_test_db")
            .withUsername("test_user")
            .withPassword("test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountService accountService;

    @Autowired
    private IdempotentTransferOrchestrator orchestrator;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void shouldGuaranteeSingleTransferUnder100ConcurrentDuplicateRetries() throws InterruptedException {
        AccountResponse alice = accountService.createAccount(new CreateAccountRequest("Alice", "USD", new BigDecimal("1000.00")));
        AccountResponse bob = accountService.createAccount(new CreateAccountRequest("Bob", "USD", new BigDecimal("500.00")));

        String sharedKey = "idem-burst-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(alice.id(), bob.id(), new BigDecimal("100.00"), null);

        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    orchestrator.processTransfer(sharedKey, request);
                    successCount.incrementAndGet();
                } catch (IdempotencyConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        AccountResponse updatedAlice = accountService.getAccount(alice.id());
        AccountResponse updatedBob = accountService.getAccount(bob.id());

        assertEquals(0, new BigDecimal("900.00").compareTo(updatedAlice.balance()));
        assertEquals(0, new BigDecimal("600.00").compareTo(updatedBob.balance()));
        assertTrue(successCount.get() >= 1);
        assertEquals(threads, successCount.get() + conflictCount.get());

        List<LedgerEntry> aliceEntries = ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(alice.id());
        assertEquals(1, aliceEntries.size());
    }

    @Test
    void shouldPreventNegativeBalanceUnder100ConcurrentDistinctTransfers() throws InterruptedException {
        AccountResponse alice = accountService.createAccount(new CreateAccountRequest("Alice", "USD", new BigDecimal("100.00")));
        AccountResponse bob = accountService.createAccount(new CreateAccountRequest("Bob", "USD", new BigDecimal("0.00")));

        TransferRequest request = new TransferRequest(alice.id(), bob.id(), new BigDecimal("15.00"), null);

        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficientFunds = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final String uniqueKey = "distinct-" + UUID.randomUUID();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    orchestrator.processTransfer(uniqueKey, request);
                    succeeded.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficientFunds.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(6, succeeded.get());
        assertEquals(94, insufficientFunds.get());
        assertEquals(0, new BigDecimal("10.00").compareTo(accountService.getAccount(alice.id()).balance()));
        assertEquals(0, new BigDecimal("90.00").compareTo(accountService.getAccount(bob.id()).balance()));
    }

    @Test
    void shouldPreventDeadlocksUnderBiDirectionalConcurrentTransfers() throws InterruptedException {
        AccountResponse alice = accountService.createAccount(new CreateAccountRequest("Alice", "USD", new BigDecimal("1000.00")));
        AccountResponse bob = accountService.createAccount(new CreateAccountRequest("Bob", "USD", new BigDecimal("1000.00")));

        int pairs = 20;
        ExecutorService executor = Executors.newFixedThreadPool(pairs * 2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(pairs * 2);
        AtomicInteger completed = new AtomicInteger();

        for (int i = 0; i < pairs; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    orchestrator.processTransfer("a-to-b-" + UUID.randomUUID(),
                            new TransferRequest(alice.id(), bob.id(), new BigDecimal("10.00"), null));
                    completed.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    startLatch.await();
                    orchestrator.processTransfer("b-to-a-" + UUID.randomUUID(),
                            new TransferRequest(bob.id(), alice.id(), new BigDecimal("10.00"), null));
                    completed.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(pairs * 2, completed.get());
        BigDecimal totalMoney = accountService.getAccount(alice.id()).balance()
                .add(accountService.getAccount(bob.id()).balance());
        assertEquals(0, new BigDecimal("2000.00").compareTo(totalMoney));
    }

    @Test
    void shouldRejectReusedIdempotencyKeyWithDifferentPayload() {
        AccountResponse alice = accountService.createAccount(new CreateAccountRequest("Alice", "USD", new BigDecimal("500.00")));
        AccountResponse bob = accountService.createAccount(new CreateAccountRequest("Bob", "USD", new BigDecimal("100.00")));

        String key = "payload-check-" + UUID.randomUUID();
        orchestrator.processTransfer(key, new TransferRequest(alice.id(), bob.id(), new BigDecimal("50.00"), null));

        assertThrows(IdempotencyConflictException.class, () ->
                orchestrator.processTransfer(key, new TransferRequest(alice.id(), bob.id(), new BigDecimal("99.00"), null))
        );
    }

    @Test
    void shouldPersistOutboxEventAtomicallyWithTransfer() {
        AccountResponse alice = accountService.createAccount(new CreateAccountRequest("Alice", "USD", new BigDecimal("500.00")));
        AccountResponse bob = accountService.createAccount(new CreateAccountRequest("Bob", "USD", new BigDecimal("100.00")));

        TransferResponse response = orchestrator.processTransfer(
                "outbox-test-" + UUID.randomUUID(),
                new TransferRequest(alice.id(), bob.id(), new BigDecimal("25.00"), "https://example.com/webhook")
        );

        boolean outboxCreated = outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getAggregateId().equals(response.transferId()));
        assertTrue(outboxCreated);
    }
}