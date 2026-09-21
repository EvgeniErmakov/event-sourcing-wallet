package com.example.wallet.repository.jdbc;

import com.example.wallet.domain.Wallet;
import com.example.wallet.repository.WalletReadModelRepository;
import com.example.wallet.service.model.WalletReadModel;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Параметризованный SQL проекции на том же DataSource, что и события.
 * BIGINT-арифметика PostgreSQL отвергает переполнение; CHECK защищает допустимое состояние фактов.
 * Репозиторий не открывает транзакций и не принимает бизнес-решений о новых командах.
 */
@Repository
public class JdbcWalletReadModelRepository implements WalletReadModelRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWalletReadModelRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<WalletReadModel> find(UUID walletId) {
        return jdbc.query("""
                SELECT wallet_id, balance_minor, currency, status, last_event_version
                FROM wallet_read_model WHERE wallet_id = :id
                """, Map.of("id", walletId), (rs, row) -> new WalletReadModel(
                rs.getObject("wallet_id", UUID.class), rs.getLong("balance_minor"), rs.getString("currency"),
                Wallet.Status.valueOf(rs.getString("status")), rs.getLong("last_event_version"))).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public void insert(UUID walletId, String currency) {
        requireWriteTransaction();
        jdbc.update("""
                INSERT INTO wallet_read_model(wallet_id, balance_minor, currency, status, last_event_version)
                VALUES (:id, 0, :currency, 'ACTIVE', 1)
                """, Map.of("id", walletId, "currency", currency));
    }

    /** Версия v применяется строго к v-1; отсутствие или повтор даёт 0 и затем ошибку проектора. */
    @Override
    public int changeBalance(UUID walletId, long deltaMinor, long eventVersion) {
        requireWriteTransaction();
        return jdbc.update("""
                UPDATE wallet_read_model
                SET balance_minor = balance_minor + :delta, last_event_version = :version
                WHERE wallet_id = :id AND last_event_version = :previous AND status = 'ACTIVE'
                """, Map.of("id", walletId, "delta", deltaMinor, "version", eventVersion, "previous", eventVersion - 1));
    }

    /** {@inheritDoc} CHECK закрытого состояния дополнительно защищает целостность применяемой истории. */
    @Override
    public int close(UUID walletId, long eventVersion) {
        requireWriteTransaction();
        return jdbc.update("""
                UPDATE wallet_read_model SET status = 'CLOSED', last_event_version = :version
                WHERE wallet_id = :id AND last_event_version = :previous AND status = 'ACTIVE'
                """, Map.of("id", walletId, "version", eventVersion, "previous", eventVersion - 1));
    }

    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Запись проекции требует транзакцию обработчика");
        }
    }
}
