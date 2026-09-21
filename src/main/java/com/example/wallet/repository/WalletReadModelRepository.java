package com.example.wallet.repository;

import com.example.wallet.service.model.WalletReadModel;
import java.util.Optional;
import java.util.UUID;

/** JDBC-контракт производной таблицы. Запись допускается только внутри транзакции команды. */
public interface WalletReadModelRepository {
    /** Читает текущую проекцию без replay или автоматического восстановления. */
    Optional<WalletReadModel> find(UUID walletId);

    /** Вставляет первое состояние; дубликат является ошибкой, upsert запрещён. */
    void insert(UUID walletId, String currency);

    /** Применяет изменение копеек при предыдущей версии; возвращает число изменённых строк. */
    int changeBalance(UUID walletId, long deltaMinor, long eventVersion);

    /** Применяет факт закрытия при предыдущей версии; возвращает число изменённых строк. */
    int close(UUID walletId, long eventVersion);

}
