package com.example.wallet.controller;

import com.example.wallet.domain.command.WalletCommand;
import com.example.wallet.dto.request.CloseWalletRequestDto;
import com.example.wallet.dto.request.CreateWalletRequestDto;
import com.example.wallet.dto.request.MoneyRequestDto;
import com.example.wallet.dto.response.EventPageResponseDto;
import com.example.wallet.dto.response.WalletResponseDto;
import com.example.wallet.service.WalletService;
import com.example.wallet.service.model.CommandReceipt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Транспортный адаптер: DTO → команда → сервис → HTTP. Бизнес-правила и SQL отсутствуют.
 * Spring MVC валидирует тело и параметры до вызова сервиса. Статус и тело команды
 * берутся из receipt, поэтому повтор сохраняет первоначальный ответ, включая 201.
 */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {
    private final WalletService service;

    public WalletController(WalletService service) {
        this.service = service;
    }

    /** Создаёт кошелёк по заданному UUID; статус и тело повтора берутся из исходного receipt. */
    @PutMapping("/{walletId}")
    public ResponseEntity<WalletResponseDto> create(@PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") UUID commandId, @Valid @RequestBody CreateWalletRequestDto body) {
        return execute(walletId, commandId, new WalletCommand.CreateWallet(body.currency()));
    }

    /** Передаёт положительную сумму в копейках и ожидаемую версию в сценарий пополнения. */
    @PostMapping("/{walletId}/deposits")
    public ResponseEntity<WalletResponseDto> deposit(@PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") UUID commandId, @Valid @RequestBody MoneyRequestDto body) {
        return execute(walletId, commandId, new WalletCommand.DepositMoney(body.amountMinor(), body.expectedVersion()));
    }

    /** Передаёт списание сервису; повтор после конфликта с новой версией здесь не выполняется. */
    @PostMapping("/{walletId}/withdrawals")
    public ResponseEntity<WalletResponseDto> withdraw(@PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") UUID commandId, @Valid @RequestBody MoneyRequestDto body) {
        return execute(walletId, commandId, new WalletCommand.WithdrawMoney(body.amountMinor(), body.expectedVersion()));
    }

    /** Передаёт намерение закрытия; состояние кошелька и баланс проверяет домен. */
    @PostMapping("/{walletId}/close")
    public ResponseEntity<WalletResponseDto> close(@PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") UUID commandId, @Valid @RequestBody CloseWalletRequestDto body) {
        return execute(walletId, commandId, new WalletCommand.CloseWallet(body.expectedVersion()));
    }

    /** Отображает текущее или историческое состояние, уже восстановленное сервисом. */
    @GetMapping("/{walletId}")
    public WalletResponseDto get(@PathVariable UUID walletId, @RequestParam(required = false) @Min(1) Long atVersion) {
        return WalletResponseDto.from(service.get(walletId, atVersion));
    }

    /** Отображает готовую страницу фактов с неизменными курсором и порядком событий. */
    @GetMapping("/{walletId}/events")
    public EventPageResponseDto history(@PathVariable UUID walletId,
            @RequestParam(defaultValue = "0") @Min(0) long afterVersion,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return EventPageResponseDto.from(service.history(walletId, afterVersion, limit));
    }

    private ResponseEntity<WalletResponseDto> execute(UUID walletId, UUID commandId, WalletCommand command) {
        CommandReceipt receipt = service.execute(walletId, commandId, command);
        return ResponseEntity.status(receipt.responseStatus()).body(WalletResponseDto.from(receipt.responseBody()));
    }
}
