package com.fintech.ledger.controller;

import com.fintech.ledger.dto.TransferRequest;
import com.fintech.ledger.dto.TransferResponse;
import com.fintech.ledger.service.IdempotentTransferOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Execute idempotent double-entry money transfers")
public class TransferController {

    private final IdempotentTransferOrchestrator orchestrator;

    public TransferController(IdempotentTransferOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    @Operation(summary = "Execute an idempotent transfer between two accounts")
    public ResponseEntity<TransferResponse> createTransfer(
            @Parameter(description = "Unique client-generated key preventing duplicate transfers", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        TransferResponse response = orchestrator.processTransfer(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}