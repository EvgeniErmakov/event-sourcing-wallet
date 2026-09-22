package com.example.wallet.repository.jdbc;

import com.example.wallet.exception.domain.CorruptHistoryException;
import com.example.wallet.exception.domain.WalletException;
import com.example.wallet.repository.CommandReceiptRepository;
import com.example.wallet.service.model.CommandReceipt;
import com.example.wallet.service.model.WalletState;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Сохраняет исходный статус и тело успешной команды в PostgreSQL.
 * Коллизия глобального command_id ожидает конкурента на PK, затем прерывает нашу
 * транзакцию. Повторное чтение выполняет сервис после rollback, никогда не этот метод.
 */
@Repository
public class JdbcCommandReceiptRepository implements CommandReceiptRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper mapper;

    public JdbcCommandReceiptRepository(NamedParameterJdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** {@inheritDoc} JSON читается в прежнюю прикладную модель, а не в HTTP DTO. */
    @Override
    public Optional<CommandReceipt> find(UUID commandId) {
        return jdbc.query("""
                SELECT command_id, request_fingerprint, response_status, response_body::text AS body, completed_at
                FROM command_receipts WHERE command_id = :id
                """, Map.of("id", commandId), (rs, row) -> {
            WalletState body;
            try {
                body = mapper.readValue(rs.getString("body"), WalletState.class);
            } catch (RuntimeException e) {
                throw new CorruptHistoryException("Повреждён сохранённый ответ команды", e);
            }
            if (body == null || body.walletId() == null || body.status() == null || body.version() < 1
                    || body.balanceMinor() < 0 || !"RUB".equals(body.currency())) {
                throw new CorruptHistoryException("Некорректный сохранённый ответ команды");
            }
            return new CommandReceipt(rs.getObject("command_id", UUID.class), rs.getString("request_fingerprint"),
                    rs.getInt("response_status"), body, rs.getObject("completed_at", OffsetDateTime.class).toInstant());
        }).stream().findFirst();
    }

    /**
     * {@inheritDoc}
     * Адресный ON CONFLICT распознаёт только PK receipt. Нулевая вставка заставляет
     * Axon откатить сохранение событий; отдельной транзакции у метода нет.
     */
    @Override
    public void insert(CommandReceipt receipt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Receipt требует транзакцию команды");
        }
        int inserted = jdbc.update("""
                INSERT INTO command_receipts(command_id, request_fingerprint, response_status, response_body, completed_at)
                VALUES (:id, :fingerprint, :status, CAST(:body AS jsonb), :completed)
                ON CONFLICT ON CONSTRAINT pk_command_receipts DO NOTHING
                """, Map.of("id", receipt.commandId(), "fingerprint", receipt.requestFingerprint(),
                "status", receipt.responseStatus(), "body", mapper.writeValueAsString(receipt.responseBody()),
                "completed", OffsetDateTime.ofInstant(receipt.completedAt(), ZoneOffset.UTC)));
        if (inserted == 0) {
            throw new WalletException(WalletException.Code.VERSION_CONFLICT, "Команда уже завершена конкурентом");
        }
    }
}
