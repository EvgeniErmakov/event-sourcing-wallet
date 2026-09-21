package com.example.wallet.controller;

import com.example.wallet.service.ProjectionHandlerService;
import com.example.wallet.service.model.ProjectionHandlerStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Технический HTTP-контроллер для учебной паузы одного экземпляра обработчика. */
@RestController
@RequestMapping("/api/projection-handler")
public class ProjectionHandlerController {
    private final ProjectionHandlerService handler;

    public ProjectionHandlerController(ProjectionHandlerService handler) {
        this.handler = handler;
    }

    @GetMapping
    public ProjectionHandlerStatus status() {
        return handler.status();
    }

    @PostMapping("/pause")
    public ProjectionHandlerStatus pause() {
        return handler.pause();
    }

    @PostMapping("/resume")
    public ProjectionHandlerStatus resume() {
        return handler.resume();
    }
}
