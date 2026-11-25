package com.fintech.ledger.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotNull(message = "Source account ID is required")
        UUID fromAccountId,

        @NotNull(message = "Destination account ID is required")
        UUID toAccountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Transfer amount must be at least 0.01")
        BigDecimal amount,

        String webhookUrl
) {}