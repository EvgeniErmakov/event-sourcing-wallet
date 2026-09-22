package com.example.wallet.service;

import com.example.wallet.service.model.ProjectionHandlerStatus;

/** Техническое управление streaming processor Axon; бизнес-команды через него не выполняются. */
public interface ProjectionHandlerService {
    ProjectionHandlerStatus status();

    ProjectionHandlerStatus pause();

    ProjectionHandlerStatus resume();
}
