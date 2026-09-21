package com.example.wallet.service.model;

/** Текущее состояние учебного обработчика проекции одного экземпляра приложения. */
public record ProjectionHandlerStatus(String status, boolean paused, boolean pauseRequested, boolean processing,
        String lastError) {
}
