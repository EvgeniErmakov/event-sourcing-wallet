--liquibase formatted sql

--changeset wallet-async:002-async-projection
--comment: Производная модель и сохранённые позиции независимого polling-обработчика.
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

CREATE TABLE projection_positions (
    wallet_id UUID CONSTRAINT pk_projection_positions PRIMARY KEY,
    last_processed_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_projection_positions_stream FOREIGN KEY (wallet_id) REFERENCES event_streams(stream_id),
    CONSTRAINT ck_projection_positions_version CHECK (last_processed_version >= 0)
);

COMMENT ON TABLE wallet_read_model IS 'Производная модель; может временно отставать от wallet_events';
COMMENT ON TABLE projection_positions IS 'Позиция async-проектора на каждый поток, 0 означает отсутствие применённых фактов';
COMMENT ON COLUMN wallet_read_model.last_event_version IS 'Последняя версия фактически применённого события';
COMMENT ON COLUMN projection_positions.last_processed_version IS 'До какой stream_version зафиксированы read model и позиция';
--rollback DROP TABLE projection_positions;
--rollback DROP TABLE wallet_read_model;
