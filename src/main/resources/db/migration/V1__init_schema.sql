-- 1. Accounts Table
CREATE TABLE accounts (
                          id UUID PRIMARY KEY,
                          owner_name VARCHAR(120) NOT NULL,
                          currency VARCHAR(3) NOT NULL DEFAULT 'USD',
                          balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
                          created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                          updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                          CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);

-- 2. Double-Entry Ledger Table (Immutable Append-Only Log)
CREATE TABLE ledger_entries (
                                id UUID PRIMARY KEY,
                                transfer_id UUID NOT NULL,
                                account_id UUID NOT NULL REFERENCES accounts(id),
                                entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
                                amount NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
                                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_transfer_id ON ledger_entries(transfer_id);
CREATE INDEX idx_ledger_account_id ON ledger_entries(account_id);

-- 3. Idempotency Records Table
CREATE TABLE idempotency_records (
                                     id UUID PRIMARY KEY,
                                     idempotency_key VARCHAR(128) NOT NULL UNIQUE,
                                     request_hash VARCHAR(64) NOT NULL,
                                     status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
                                     response_status INT,
                                     response_body TEXT,
                                     created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                                     updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 4. Transactional Outbox Table for Async Webhooks
CREATE TABLE outbox_events (
                               id UUID PRIMARY KEY,
                               aggregate_type VARCHAR(50) NOT NULL,
                               aggregate_id UUID NOT NULL,
                               event_type VARCHAR(50) NOT NULL,
                               payload TEXT NOT NULL,
                               webhook_url VARCHAR(500) NOT NULL,
                               status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
                               retry_count INT NOT NULL DEFAULT 0,
                               next_retry_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                               created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_pending ON outbox_events(status, next_retry_at) WHERE status = 'PENDING';