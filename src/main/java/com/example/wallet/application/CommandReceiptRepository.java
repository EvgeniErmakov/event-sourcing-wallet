package com.example.wallet.application;

import java.util.Optional;
import java.util.UUID;

/** Постоянная идемпотентность: один глобальный ключ соответствует одному содержанию команды. */
public interface CommandReceiptRepository {
    /** Чтение после конфликта обязано выполняться в новой транзакции после rollback. */
    Optional<CommandReceipt> find(UUID commandId);
    /** Запись вместе с событием; коллизия ключа прерывает всю транзакцию команды. */
    void save(CommandReceipt receipt);
}
