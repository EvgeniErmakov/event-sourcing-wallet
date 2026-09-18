package com.example.wallet.repository;

import com.example.wallet.service.model.CommandReceipt;
import java.util.Optional;
import java.util.UUID;

/** Постоянная идемпотентность: один глобальный ключ соответствует одному содержанию команды. */
public interface CommandReceiptRepository {
    /** Чтение после конфликта обязано выполняться в новой транзакции после rollback. */
    Optional<CommandReceipt> find(UUID commandId);

    /**
     * Только вставляет результат вместе с событием; никогда не перезаписывает существующий receipt.
     * Коллизия ключа прерывает транзакцию команды, после чего сервис отдельно читает результат конкурента.
     */
    void insert(CommandReceipt receipt);
}
