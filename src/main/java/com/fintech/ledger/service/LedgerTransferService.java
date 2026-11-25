package com.fintech.ledger.service;

import com.fintech.ledger.domain.Account;
import com.fintech.ledger.domain.LedgerEntry;
import com.fintech.ledger.domain.OutboxEvent;
import com.fintech.ledger.dto.TransferRequest;
import com.fintech.ledger.dto.TransferResponse;
import com.fintech.ledger.exception.InsufficientFundsException;
import com.fintech.ledger.exception.ResourceNotFoundException;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.LedgerEntryRepository;
import com.fintech.ledger.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LedgerTransferService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public LedgerTransferService(AccountRepository accountRepository,
                                 LedgerEntryRepository ledgerEntryRepository,
                                 OutboxEventRepository outboxEventRepository,
                                 ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TransferResponse executeTransfer(TransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new IllegalArgumentException("Source and destination accounts must be different");
        }

        // 1. Lock both accounts deterministically (ORDER BY id ASC) to prevent deadlocks
        List<UUID> accountIds = List.of(request.fromAccountId(), request.toAccountId());
        Map<UUID, Account> lockedAccounts = accountRepository.findAllByIdInForUpdate(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        Account source = lockedAccounts.get(request.fromAccountId());
        if (source == null) {
            throw new ResourceNotFoundException("Source account not found: " + request.fromAccountId());
        }

        Account destination = lockedAccounts.get(request.toAccountId());
        if (destination == null) {
            throw new ResourceNotFoundException("Destination account not found: " + request.toAccountId());
        }

        if (!source.getCurrency().equals(destination.getCurrency())) {
            throw new IllegalArgumentException("Currency mismatch between accounts");
        }

        if (source.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException("Insufficient funds in source account: " + source.getId());
        }

        // 2. Update balances
        source.debit(request.amount());
        destination.credit(request.amount());

        // 3. Record immutable double-entry ledger entries (DEBIT + CREDIT)
        UUID transferId = UUID.randomUUID();
        LedgerEntry debitEntry = new LedgerEntry(transferId, source.getId(), LedgerEntry.EntryType.DEBIT, request.amount());
        LedgerEntry creditEntry = new LedgerEntry(transferId, destination.getId(), LedgerEntry.EntryType.CREDIT, request.amount());
        ledgerEntryRepository.saveAll(List.of(debitEntry, creditEntry));

        TransferResponse response = new TransferResponse(
                transferId,
                source.getId(),
                destination.getId(),
                request.amount(),
                source.getCurrency(),
                "COMPLETED",
false,
OffsetDateTime.now()
//                OffsetDateTime.now()
        );

        // 4. Transactional Outbox: persist webhook event in the exact same ACID transaction
        if (request.webhookUrl() != null && !request.webhookUrl().isBlank()) {
            String eventPayload = serializePayload(response);
            OutboxEvent event = new OutboxEvent(
                    "TRANSFER",
                    transferId,
                    "TRANSFER_COMPLETED",
                    eventPayload,
                    request.webhookUrl()
            );
            outboxEventRepository.save(event);
        }

        return response;
    }

    private String serializePayload(TransferResponse response) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "event", "TRANSFER_COMPLETED",
                    "data", response
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}