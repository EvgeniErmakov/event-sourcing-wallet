--liquibase formatted sql
--changeset wallet-axon:001
--comment: AggregateEventEntry Axon 5.3.2; Hibernate standard physical naming, PostgreSQL @Lob uses OID.
CREATE SEQUENCE "aggregate-event-global-index-sequence" START WITH 1 INCREMENT BY 1;
CREATE TABLE "AggregateEventEntry" (
    "globalIndex" BIGINT PRIMARY KEY,
    "aggregateType" VARCHAR(255),
    "aggregateIdentifier" VARCHAR(255),
    "aggregateSequenceNumber" BIGINT,
    type VARCHAR(255) NOT NULL,
    version VARCHAR(255) NOT NULL,
    timestamp VARCHAR(255) NOT NULL,
    payload OID NOT NULL,
    metadata OID,
    identifier VARCHAR(255) NOT NULL,
    CONSTRAINT uq_axon_aggregate_version UNIQUE ("aggregateIdentifier", "aggregateSequenceNumber")
);
-- JdbcTokenStore использует некавыченные имена; PostgreSQL приводит их к нижнему регистру.
CREATE TABLE TokenEntry (
    processorName VARCHAR(255) NOT NULL,
    segment INTEGER NOT NULL,
    mask INTEGER NOT NULL,
    token BYTEA,
    tokenType VARCHAR(255),
    timestamp VARCHAR(255),
    owner VARCHAR(255),
    PRIMARY KEY (processorName, segment)
);
-- Единственный segment начинается до первого события. Resume не сбрасывает эту запись.
INSERT INTO TokenEntry (processorName, segment, mask, timestamp)
VALUES ('wallet-projection', 0, 0, '1970-01-01T00:00:00Z');
-- Штатный ConfigToken содержит идентификатор конкретного хранилища, не позицию событий.
INSERT INTO TokenEntry (processorName, segment, mask, timestamp, tokenType, token)
VALUES ('__config', 0, 0, '1970-01-01T00:00:00Z',
    'org.axonframework.messaging.eventhandling.processing.streaming.token.store.ConfigToken',
    convert_to(json_build_object('config', json_build_object('id', gen_random_uuid()::text))::text, 'UTF8'));

