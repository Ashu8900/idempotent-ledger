package com.fintech.ledger.service;

import com.fintech.ledger.domain.Account;
import com.fintech.ledger.domain.LedgerEntry;
import com.fintech.ledger.dto.AccountResponse;
import com.fintech.ledger.dto.CreateAccountRequest;
import com.fintech.ledger.exception.ResourceNotFoundException;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AccountService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = new Account(
                UUID.randomUUID(),
                request.ownerName(),
                request.currency().toUpperCase(),
                request.initialBalance()
        );
        return AccountResponse.from(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> getAccountLedger(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new ResourceNotFoundException("Account not found: " + accountId);
        }
        return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
    }
}