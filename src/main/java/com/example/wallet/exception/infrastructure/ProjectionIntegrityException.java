package com.example.wallet.exception.infrastructure;

/** Нарушение подготовки или последовательности проекции; требует rollback, а не повтора бизнес-команды. */
public class ProjectionIntegrityException extends RuntimeException {
    public ProjectionIntegrityException(String message) {
        super(message);
    }

    public ProjectionIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
