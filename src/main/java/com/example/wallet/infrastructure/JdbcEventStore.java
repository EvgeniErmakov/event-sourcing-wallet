package com.example.wallet.infrastructure;

import com.example.wallet.application.EventStore;
import com.example.wallet.application.StoredEvent;
import com.example.wallet.domain.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.wallet.domain.WalletException.Code.*;

/**
 * PostgreSQL event store: SELECT/INSERT для фактов, UPDATE только технической версии.
 * Имена конфликтующих ограничений адресованы явно: неожиданные SQL-ошибки не маскируются
 * под конкурентный конфликт. Все параметры данных передаются через JDBC bindings.
 */
@Repository
public class JdbcEventStore implements EventStore {
    private static final String EVENT_COLUMNS = """
            e.event_id, e.stream_id, e.stream_version, e.event_type, e.schema_version,
            e.payload::text AS payload, e.occurred_at, e.command_id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final EventSerializer serializer;

    public JdbcEventStore(NamedParameterJdbcTemplate jdbc, EventSerializer serializer) {
        this.jdbc = jdbc;
        this.serializer = serializer;
    }

    /**
     * Один SELECT даёт согласованный снимок метаданных и всех событий при READ COMMITTED.
     * Версия агрегата получается из фактов; current_version служит только проверкой
     * целостности. LEFT JOIN позволяет обнаружить ошибочный пустой сохранённый поток.
     */
    @Override
    public List<StoredEvent> load(UUID walletId) {
        return jdbc.query("SELECT s.current_version, " + EVENT_COLUMNS + """
                 FROM event_streams s LEFT JOIN wallet_events e ON e.stream_id = s.stream_id
                 WHERE s.stream_id = :id ORDER BY e.stream_version ASC
                """, Map.of("id", walletId), rs -> {
            List<StoredEvent> result = new ArrayList<>();
            long expected = 1;
            long metadataVersion = 0;
            while (rs.next()) {
                metadataVersion = rs.getLong("current_version");
                if (rs.getObject("event_id") == null) throw new CorruptHistoryException("Пустой сохранённый поток");
                StoredEvent event = map(rs);
                if (event.streamVersion() != expected++) throw new CorruptHistoryException("Разрыв версий потока");
                result.add(event);
            }
            if (!result.isEmpty() && metadataVersion != result.getLast().streamVersion()) {
                throw new CorruptHistoryException("Метаданные версии не совпадают с историей");
            }
            return List.copyOf(result);
        });
    }

    /**
     * expectedVersion — версия прочитанного потока, уже сопоставленная с командой.
     * UPDATE-CAS блокирует строку и повторно проверяет условие после ожидания конкурента.
     * Нулевой результат не допускает запись события. Для создания PK разрешает гонку
     * вставок; ON CONFLICT направлен только на этот PK. Исключение заставляет сервис
     * откатить и CAS, и INSERT, и receipt. Здесь нет commit и чтения результата конкурента.
     */
    @Override
    public void append(UUID walletId, long expectedVersion, WalletEvent event, UUID eventId,
                       UUID commandId, Instant occurredAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("append требует транзакцию сервиса");
        }
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Версия вне диапазона append");
        }
        var params = new MapSqlParameterSource("id", walletId).addValue("expected", expectedVersion);
        if (expectedVersion == 0) {
            int inserted = jdbc.update("""
                    INSERT INTO event_streams(stream_id, current_version) VALUES (:id, 0)
                    ON CONFLICT ON CONSTRAINT pk_event_streams DO NOTHING
                    """, params);
            if (inserted == 0) throw new WalletException(WALLET_ALREADY_EXISTS, "Кошелёк уже создан конкурентом");
        }
        int updated = jdbc.update("""
                UPDATE event_streams SET current_version = current_version + 1
                WHERE stream_id = :id AND current_version = :expected
                """, params);
        if (updated == 0) throw new WalletException(VERSION_CONFLICT, "Поток изменён конкурентом");
        EventSerializer.Encoded encoded = serializer.encode(event);
        params.addValue("eventId", eventId).addValue("version", expectedVersion + 1)
                .addValue("type", encoded.eventType()).addValue("schema", encoded.schemaVersion())
                .addValue("payload", encoded.payload()).addValue("command", commandId)
                .addValue("occurred", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        int inserted = jdbc.update("""
                INSERT INTO wallet_events(event_id, stream_id, stream_version, event_type,
                    schema_version, payload, occurred_at, command_id)
                VALUES (:eventId, :id, :version, :type, :schema, CAST(:payload AS jsonb), :occurred, :command)
                ON CONFLICT ON CONSTRAINT uq_wallet_events_stream_version DO NOTHING
                """, params);
        if (inserted == 0) throw new WalletException(VERSION_CONFLICT, "Версия события уже занята");
    }

    @Override
    public boolean exists(UUID walletId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM wallet_events WHERE stream_id = :id)",
                Map.of("id", walletId), Boolean.class));
    }

    @Override
    public List<StoredEvent> readPage(UUID walletId, long afterVersion, int limit) {
        List<StoredEvent> page = jdbc.query("SELECT " + EVENT_COLUMNS + """
                 FROM wallet_events e WHERE e.stream_id = :id AND e.stream_version > :after
                 ORDER BY e.stream_version ASC LIMIT :limit
                """, Map.of("id", walletId, "after", afterVersion, "limit", limit), (rs, row) -> map(rs));
        long previous = afterVersion;
        for (StoredEvent event : page) {
            if (event.streamVersion() != previous + 1) throw new CorruptHistoryException("Разрыв страницы истории");
            previous = event.streamVersion();
        }
        return List.copyOf(page);
    }

    private StoredEvent map(ResultSet rs) throws SQLException {
        String type = rs.getString("event_type");
        int schema = rs.getInt("schema_version");
        return new StoredEvent(rs.getObject("event_id", UUID.class), rs.getObject("stream_id", UUID.class),
                rs.getLong("stream_version"), type, schema, serializer.decode(type, schema, rs.getString("payload")),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getObject("command_id", UUID.class));
    }
}
