package com.fintech.ledger.controller;

import com.fintech.ledger.domain.LedgerEntry;
import com.fintech.ledger.dto.AccountResponse;
import com.fintech.ledger.dto.CreateAccountRequest;
import com.fintech.ledger.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Manage ledger accounts and inspect audit entries")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Create a new ledger account with an initial balance")
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get account details and current balance")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID accountId) {
        return ResponseEntity.ok(accountService.getAccount(accountId));
    }

    @GetMapping("/{accountId}/ledger")
    @Operation(summary = "Get immutable double-entry ledger history for an account")
    public ResponseEntity<List<LedgerEntry>> getAccountLedger(@PathVariable UUID accountId) {
        return ResponseEntity.ok(accountService.getAccountLedger(accountId));
    }
}