package com.example.wallet.service.model;

/**
 * Состояние processor текущего экземпляра. processing означает включённый processor
 * либо ещё не завершённый shutdown, а не точное число выполняющихся JDBC-запросов.
 * Подтверждённая пауза определяется paused; запрос остановки — pauseRequested.
 */
public record ProjectionHandlerStatus(String status, boolean paused, boolean pauseRequested, boolean processing,
        String lastError) {
}
