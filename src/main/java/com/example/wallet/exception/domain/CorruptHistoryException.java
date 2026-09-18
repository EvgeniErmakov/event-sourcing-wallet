package com.example.wallet.exception.domain;

/** Нарушение целостности сохранённых фактов: это серверная ошибка, а не отказ новой команды. */
public final class CorruptHistoryException extends RuntimeException {
    public CorruptHistoryException(String message) {
        super(message);
    }

    public CorruptHistoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
