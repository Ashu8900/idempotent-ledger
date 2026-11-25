package com.fintech.ledger.dto;

import com.fintech.ledger.domain.Account;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String ownerName,
        String currency,
        BigDecimal balance,
        OffsetDateTime createdAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerName(),
                account.getCurrency(),
                account.getBalance(),
                account.getCreatedAt()
        );
    }
}