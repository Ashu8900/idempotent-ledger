package com.fintech.ledger.dto;

import com.fintech.ledger.domain.LedgerEntry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID transferId,
        UUID accountId,
        String entryType,
        BigDecimal amount,
        OffsetDateTime createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getTransferId(),
                entry.getAccountId(),
                entry.getEntryType().name(),
                entry.getAmount(),
                entry.getCreatedAt()
        );
    }
}