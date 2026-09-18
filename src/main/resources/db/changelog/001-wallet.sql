--liquibase formatted sql

--changeset wallet:001-event-streams
--comment: Техническая версия потока для атомарного optimistic locking; баланс здесь не хранится.
CREATE TABLE event_streams (
    stream_id UUID CONSTRAINT pk_event_streams PRIMARY KEY,
    current_version BIGINT NOT NULL,
    CONSTRAINT ck_event_streams_version CHECK (current_version >= 0)
);
COMMENT ON TABLE event_streams IS 'Технические метаданные потока, не источник бизнес-состояния';
COMMENT ON COLUMN event_streams.stream_id IS 'UUID кошелька; PK разрешает гонку создания';
COMMENT ON COLUMN event_streams.current_version IS 'Версия для UPDATE-CAS, 0 допустим только внутри транзакции создания';
COMMENT ON CONSTRAINT pk_event_streams ON event_streams IS 'Один поток на кошелёк';
COMMENT ON CONSTRAINT ck_event_streams_version ON event_streams IS 'Версия не может быть отрицательной';

--changeset wallet:002-wallet-events
--comment: Неизменяемые факты; уникальность версии защищает историю и индексирует последовательный replay.
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
COMMENT ON TABLE wallet_events IS 'Единственный источник состояния Wallet; приложение только вставляет и читает';
COMMENT ON COLUMN wallet_events.event_id IS 'UUID факта, созданный вне replay';
COMMENT ON COLUMN wallet_events.stream_id IS 'Кошелёк, которому принадлежит факт';
COMMENT ON COLUMN wallet_events.stream_version IS 'Порядок внутри кошелька с 1; непрерывность обеспечивает CAS и общая транзакция';
COMMENT ON COLUMN wallet_events.event_type IS 'Стабильное имя из явного реестра, не имя Java-класса';
COMMENT ON COLUMN wallet_events.schema_version IS 'Версия формата payload, независимая от версии потока';
COMMENT ON COLUMN wallet_events.payload IS 'Бизнес-поля факта; envelope хранится в отдельных столбцах';
COMMENT ON COLUMN wallet_events.occurred_at IS 'Время принятия факта, не критерий сортировки';
COMMENT ON COLUMN wallet_events.command_id IS 'Глобальный ключ породившей факт команды; receipt вставляется в той же транзакции';
COMMENT ON CONSTRAINT pk_wallet_events ON wallet_events IS 'Уникальная идентичность факта';
COMMENT ON CONSTRAINT fk_wallet_events_stream ON wallet_events IS 'Факт не существует без потока; каскадное удаление не применяется';
COMMENT ON CONSTRAINT uq_wallet_events_stream_version ON wallet_events IS 'Один факт на позицию; индекс обслуживает ORDER BY stream_version';
COMMENT ON CONSTRAINT ck_wallet_events_version ON wallet_events IS 'События начинаются с версии 1';
COMMENT ON CONSTRAINT ck_wallet_events_schema ON wallet_events IS 'Версия формата положительна; поддерживаемые значения проверяет сериализатор';
COMMENT ON CONSTRAINT ck_wallet_events_payload ON wallet_events IS 'Payload всегда JSON-объект, в том числе пустой для закрытия';
COMMENT ON CONSTRAINT ck_wallet_events_type ON wallet_events IS 'Тип обязателен; неизвестные типы отклоняются при чтении';

--changeset wallet:003-command-receipts
--comment: Успешный результат команды для постоянной идемпотентности; фиксация вместе с событием и CAS.
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
COMMENT ON TABLE command_receipts IS 'Завершённые успешные ответы; не источник текущего состояния кошелька';
COMMENT ON COLUMN command_receipts.command_id IS 'Глобальный Idempotency-Key, переживающий перезапуск';
COMMENT ON COLUMN command_receipts.request_fingerprint IS 'SHA-256 нормализованной команды с типом, walletId, полями и expectedVersion';
COMMENT ON COLUMN command_receipts.response_status IS 'Первоначальный HTTP-статус успеха';
COMMENT ON COLUMN command_receipts.response_body IS 'Первоначальное тело ответа, даже если кошелёк уже изменился';
COMMENT ON COLUMN command_receipts.completed_at IS 'Время формирования результата команды';
COMMENT ON CONSTRAINT pk_command_receipts ON command_receipts IS 'Конкурирующие команды с одним ключом не могут обе зафиксироваться';
COMMENT ON CONSTRAINT ck_command_receipts_fingerprint ON command_receipts IS 'SHA-256 в шестнадцатеричной записи';
COMMENT ON CONSTRAINT ck_command_receipts_status ON command_receipts IS 'Ошибки не кэшируются; только статусы успеха первой версии API';
COMMENT ON CONSTRAINT ck_command_receipts_body ON command_receipts IS 'Ответ кошелька хранится JSON-объектом';
