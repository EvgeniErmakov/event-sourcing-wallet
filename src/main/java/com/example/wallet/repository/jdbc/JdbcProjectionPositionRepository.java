package com.example.wallet.repository.jdbc;

import com.example.wallet.repository.ProjectionPositionRepository;
import com.example.wallet.exception.infrastructure.ProjectionIntegrityException;
import com.example.wallet.service.model.ProjectionPosition;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Хранит курсор отдельно от read model. SELECT FOR UPDATE сериализует два экземпляра
 * приложения для одного кошелька, а повторная проверка версии выполняется внутри той же транзакции.
 */
@Repository
public class JdbcProjectionPositionRepository implements ProjectionPositionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcProjectionPositionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(UUID walletId) {
        requireWriteTransaction();
        jdbc.update("""
                INSERT INTO projection_positions(wallet_id, last_processed_version)
                VALUES (:id, 0)
                """, Map.of("id", walletId));
    }

    @Override
    public List<UUID> findWalletIds() {
        return jdbc.query("""
                SELECT wallet_id FROM projection_positions
                ORDER BY wallet_id
                """, Map.of(), (rs, row) -> rs.getObject("wallet_id", UUID.class));
    }

    @Override
    public ProjectionPosition lock(UUID walletId) {
        requireWriteTransaction();
        return jdbc.query("""
                SELECT wallet_id, last_processed_version
                FROM projection_positions
                WHERE wallet_id = :id
                FOR UPDATE
                """, Map.of("id", walletId), (rs, row) -> new ProjectionPosition(
                rs.getObject("wallet_id", UUID.class), rs.getLong("last_processed_version"))).stream()
                .findFirst()
                .orElseThrow(() -> new ProjectionIntegrityException("Позиция проекции отсутствует: walletId=" + walletId));
    }

    @Override
    public void update(UUID walletId, long expectedPreviousVersion, long newVersion) {
        requireWriteTransaction();
        int updated = jdbc.update("""
                UPDATE projection_positions
                SET last_processed_version = :newVersion
                WHERE wallet_id = :id AND last_processed_version = :expected
                """, Map.of("id", walletId, "expected", expectedPreviousVersion, "newVersion", newVersion));
        if (updated != 1) {
            throw new ProjectionIntegrityException("Позиция проекции изменилась конкурентно: walletId=" + walletId);
        }
    }

    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Позиция проекции требует транзакцию записи");
        }
    }
}
