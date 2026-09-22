package com.example.wallet.service.impl;

import com.example.wallet.domain.command.DispatchWalletCommand;
import com.example.wallet.domain.command.WalletCommand;
import com.example.wallet.exception.domain.WalletException;
import com.example.wallet.repository.CommandReceiptRepository;
import com.example.wallet.service.CommandFingerprint;
import com.example.wallet.service.WalletCommandService;
import com.example.wallet.service.model.CommandReceipt;
import java.util.UUID;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** HTTP-адаптер command gateway. Транзакция начинается внутри Axon, а не вокруг отправки команды. */
@Service
public class WalletCommandServiceImpl implements WalletCommandService {
    private final CommandGateway gateway;
    private final CommandReceiptRepository receipts;
    private final TransactionTemplate read;

    public WalletCommandServiceImpl(CommandGateway gateway, CommandReceiptRepository receipts,
            PlatformTransactionManager manager) {
        this.gateway = gateway;
        this.receipts = receipts;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Gateway завершается после UnitOfWork commit, включая JPA flush. Денежных retry здесь нет. */
    @Override
    public CommandReceipt execute(UUID walletId, UUID commandId, WalletCommand command) {
        String fingerprint = CommandFingerprint.of(walletId, command);
        var previous = read.execute(status -> receipts.find(commandId));
        if (previous.isPresent()) return matching(previous.get(), fingerprint);
        try {
            return gateway.sendAndWait(new DispatchWalletCommand(walletId, commandId, fingerprint, command), CommandReceipt.class);
        } catch (RuntimeException failure) {
            // Gateway уже завершил rollback. Новый snapshot видит receipt победившего конкурента.
            var concurrent = read.execute(status -> receipts.find(commandId));
            if (concurrent.isPresent()) return matching(concurrent.get(), fingerprint);
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof WalletException domain) throw domain;
                if (cause instanceof org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException) {
                    throw new WalletException(WalletException.Code.VERSION_CONFLICT, "Версия кошелька изменилась");
                }
            }
            throw failure;
        }
    }

    private CommandReceipt matching(CommandReceipt receipt, String fingerprint) {
        if (!receipt.requestFingerprint().equals(fingerprint)) {
            throw new WalletException(WalletException.Code.IDEMPOTENCY_KEY_REUSED, "Ключ уже использован для другой команды");
        }
        return receipt;
    }
}
