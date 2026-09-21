--liquibase formatted sql

--changeset wallet:ES-1-wallet-read-model
--comment: Синхронная производная модель CQRS; строки создаются и обновляются вместе с новыми событиями.
CREATE TABLE wallet_read_model (
    wallet_id UUID CONSTRAINT pk_wallet_read_model PRIMARY KEY,
    balance_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    last_event_version BIGINT NOT NULL,
    CONSTRAINT fk_wallet_read_model_stream FOREIGN KEY (wallet_id) REFERENCES event_streams(stream_id),
    CONSTRAINT ck_wallet_read_model_balance CHECK (balance_minor >= 0),
    CONSTRAINT ck_wallet_read_model_currency CHECK (currency = 'RUB'),
    CONSTRAINT ck_wallet_read_model_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT ck_wallet_read_model_version CHECK (last_event_version > 0),
    CONSTRAINT ck_wallet_read_model_closed CHECK (status <> 'CLOSED' OR balance_minor = 0)
);
COMMENT ON TABLE wallet_read_model IS 'Производная синхронная проекция; источник истины — wallet_events';
COMMENT ON COLUMN wallet_read_model.wallet_id IS 'UUID кошелька, один ряд на поток';
COMMENT ON COLUMN wallet_read_model.balance_minor IS 'Остаток в копейках после применения last_event_version';
COMMENT ON COLUMN wallet_read_model.currency IS 'Валюта из WalletCreated, только RUB';
COMMENT ON COLUMN wallet_read_model.status IS 'Статус после применения фактов';
COMMENT ON COLUMN wallet_read_model.last_event_version IS 'Последняя применённая stream_version, без пропусков';
--rollback DROP TABLE wallet_read_model;
