package com.fintech.ledger.service;

import com.fintech.ledger.domain.IdempotencyRecord;
import com.fintech.ledger.dto.TransferRequest;
import com.fintech.ledger.dto.TransferResponse;
import com.fintech.ledger.exception.IdempotencyConflictException;
import com.fintech.ledger.repository.IdempotencyRecordRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class IdempotentTransferOrchestrator {

    private final IdempotencyRecordRepository idempotencyRepository;
    private final LedgerTransferService ledgerTransferService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public IdempotentTransferOrchestrator(IdempotencyRecordRepository idempotencyRepository,
                                          LedgerTransferService ledgerTransferService,
                                          TransactionTemplate transactionTemplate,
                                          ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.ledgerTransferService = ledgerTransferService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public TransferResponse processTransfer(String idempotencyKey, TransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        String requestHash = computeSha256(request);

        // Step 1: Check existing record or atomically claim IN_PROGRESS lock
        Optional<IdempotencyRecord> existing = idempotencyRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return handleExistingRecord(existing.get(), requestHash);
        }

        IdempotencyRecord claimedRecord;
        try {
            claimedRecord = transactionTemplate.execute(status ->
                    idempotencyRepository.saveAndFlush(new IdempotencyRecord(idempotencyKey, requestHash))
            );
        } catch (DataIntegrityViolationException ex) {
            // Another concurrent thread inserted the exact same key microseconds ago
            IdempotencyRecord concurrentRecord = idempotencyRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IdempotencyConflictException("Concurrent request in progress for key: " + idempotencyKey));
            return handleExistingRecord(concurrentRecord, requestHash);
        }

        // Step 2: Execute the double-entry ledger transfer
        try {
            TransferResponse response = ledgerTransferService.executeTransfer(request);
            String responseJson = objectMapper.writeValueAsString(response);

            transactionTemplate.executeWithoutResult(status -> {
                IdempotencyRecord rec = idempotencyRepository.findById(claimedRecord.getId()).orElseThrow();
                rec.markCompleted(201, responseJson);
                idempotencyRepository.save(rec);
            });

            return response;
        } catch (RuntimeException ex) {
            // Remove IN_PROGRESS record so transient failures (e.g., temporary insufficient funds) can be retried cleanly
            transactionTemplate.executeWithoutResult(status ->
                    idempotencyRepository.deleteById(claimedRecord.getId())
            );
            throw ex;
        }
    }

    private TransferResponse handleExistingRecord(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("Idempotency-Key already used with a different request payload");
        }

        if (record.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
            throw new IdempotencyConflictException("A transfer with this Idempotency-Key is currently being processed");
        }

        if (record.getStatus() == IdempotencyRecord.Status.COMPLETED) {
            try {
                return objectMapper.readValue(record.getResponseBody(), TransferResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize cached idempotent response", e);
            }
        }

        throw new IdempotencyConflictException("Previous request with this Idempotency-Key failed");
    }

    private String computeSha256(TransferRequest request) {
        try {
            String canonical = request.fromAccountId() + "|" + request.toAccountId() + "|" +
                    request.amount().stripTrailingZeros().toPlainString() + "|" +
                    (request.webhookUrl() == null ? "" : request.webhookUrl());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}