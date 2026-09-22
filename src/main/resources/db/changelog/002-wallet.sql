--liquibase formatted sql
--changeset wallet-axon:002
--comment: Собственные JDBC receipts и read model; источник истины находится в Axon.
CREATE TABLE command_receipts (
    command_id UUID CONSTRAINT pk_command_receipts PRIMARY KEY,
    request_fingerprint TEXT NOT NULL,
    response_status INTEGER NOT NULL,
    response_body JSONB NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_command_receipts_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_command_receipts_status CHECK (response_status IN (200, 201)),
    CONSTRAINT ck_command_receipts_body CHECK (jsonb_typeof(response_body) = 'object')
);


CREATE TABLE wallet_read_model (
    wallet_id UUID CONSTRAINT pk_wallet_read_model PRIMARY KEY,
    balance_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    last_event_version BIGINT NOT NULL,
    CONSTRAINT ck_wallet_read_model_balance CHECK (balance_minor >= 0),
    CONSTRAINT ck_wallet_read_model_currency CHECK (currency = 'RUB'),
    CONSTRAINT ck_wallet_read_model_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT ck_wallet_read_model_version CHECK (last_event_version > 0),
    CONSTRAINT ck_wallet_read_model_closed CHECK (status <> 'CLOSED' OR balance_minor = 0)
);

