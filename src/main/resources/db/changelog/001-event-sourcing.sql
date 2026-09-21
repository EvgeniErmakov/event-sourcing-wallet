--liquibase formatted sql

--changeset wallet-async:001-event-sourcing
--comment: Начальная событийная схема этапа 03; применяется только на чистой учебной БД.
CREATE TABLE event_streams (
    stream_id UUID CONSTRAINT pk_event_streams PRIMARY KEY,
    current_version BIGINT NOT NULL,
    CONSTRAINT ck_event_streams_version CHECK (current_version >= 0)
);

CREATE TABLE wallet_events (
    event_id UUID CONSTRAINT pk_wallet_events PRIMARY KEY,
    stream_id UUID NOT NULL,
    stream_version BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    command_id UUID NOT NULL,
    CONSTRAINT fk_wallet_events_stream FOREIGN KEY (stream_id) REFERENCES event_streams(stream_id),
    CONSTRAINT uq_wallet_events_stream_version UNIQUE (stream_id, stream_version),
    CONSTRAINT ck_wallet_events_version CHECK (stream_version > 0),
    CONSTRAINT ck_wallet_events_schema CHECK (schema_version > 0),
    CONSTRAINT ck_wallet_events_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_wallet_events_type CHECK (length(event_type) > 0)
);

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

COMMENT ON TABLE wallet_events IS 'Единственный источник истины; события только добавляются и читаются';
COMMENT ON TABLE command_receipts IS 'Успешные ответы для постоянной идемпотентности, не read model';
--rollback DROP TABLE command_receipts;
--rollback DROP TABLE wallet_events;
--rollback DROP TABLE event_streams;
