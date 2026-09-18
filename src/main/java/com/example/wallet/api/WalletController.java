package com.example.wallet.api;

import com.example.wallet.application.*;
import com.example.wallet.domain.WalletCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Транспортный адаптер: DTO → команда → сервис → HTTP. Бизнес-правила и SQL отсутствуют.
 * Spring MVC валидирует тело и параметры до вызова сервиса. Статус и тело команды
 * берутся из receipt, поэтому повтор сохраняет первоначальный ответ, включая 201.
 */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {
    private final WalletService service;

    public WalletController(WalletService service) { this.service = service; }

    @PutMapping("/{walletId}")
    public ResponseEntity<WalletState> create(@PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") UUID commandId, @Valid @RequestBody WalletRequests.Create body) {
        return execute(walletId, commandId, new WalletCommand.CreateWallet(body.currency()));
    }

    @PostMapping("/{walletId}/deposits")
    public ResponseEntity<WalletState> deposit(@PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") UUID commandId, @Valid @RequestBody WalletRequests.Money body) {
        return execute(walletId, commandId, new WalletCommand.DepositMoney(body.amountMinor(), body.expectedVersion()));
    }

    @PostMapping("/{walletId}/withdrawals")
    public ResponseEntity<WalletState> withdraw(@PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") UUID commandId, @Valid @RequestBody WalletRequests.Money body) {
        return execute(walletId, commandId, new WalletCommand.WithdrawMoney(body.amountMinor(), body.expectedVersion()));
    }

    @PostMapping("/{walletId}/close")
    public ResponseEntity<WalletState> close(@PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") UUID commandId, @Valid @RequestBody WalletRequests.Close body) {
        return execute(walletId, commandId, new WalletCommand.CloseWallet(body.expectedVersion()));
    }

    @GetMapping("/{walletId}")
    public WalletState get(@PathVariable UUID walletId, @RequestParam(required = false) @Min(1) Long atVersion) {
        return service.get(walletId, atVersion);
    }

    @GetMapping("/{walletId}/events")
    public EventPage history(@PathVariable UUID walletId,
            @RequestParam(defaultValue = "0") @Min(0) long afterVersion,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return service.history(walletId, afterVersion, limit);
    }

    private ResponseEntity<WalletState> execute(UUID walletId, UUID commandId, WalletCommand command) {
        CommandReceipt receipt = service.execute(walletId, commandId, command);
        return ResponseEntity.status(receipt.responseStatus()).body(receipt.responseBody());
    }
}
