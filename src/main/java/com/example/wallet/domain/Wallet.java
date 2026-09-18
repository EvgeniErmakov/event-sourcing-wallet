package com.example.wallet.domain;

import static com.example.wallet.exception.domain.WalletException.Code.BALANCE_OVERFLOW;
import static com.example.wallet.exception.domain.WalletException.Code.INSUFFICIENT_FUNDS;
import static com.example.wallet.exception.domain.WalletException.Code.INVALID_REQUEST;
import static com.example.wallet.exception.domain.WalletException.Code.NON_ZERO_BALANCE;
import static com.example.wallet.exception.domain.WalletException.Code.VERSION_CONFLICT;
import static com.example.wallet.exception.domain.WalletException.Code.WALLET_ALREADY_EXISTS;
import static com.example.wallet.exception.domain.WalletException.Code.WALLET_CLOSED;
import static com.example.wallet.exception.domain.WalletException.Code.WALLET_NOT_FOUND;

import com.example.wallet.domain.command.WalletCommand;
import com.example.wallet.domain.event.WalletEvent;
import com.example.wallet.exception.domain.CorruptHistoryException;
import com.example.wallet.exception.domain.WalletException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат, состояние которого существует только как результат применения событий.
 * До первого WalletCreated версия равна 0 и кошелёк не существует.
 * Объект используется внутри одного сценария и не разделяется между потоками.
 */
public final class Wallet {
    private final UUID walletId;
    private long balanceMinor;
    private String currency;
    private Status status;
    private long version;

    private Wallet(UUID walletId) {
        this.walletId = Objects.requireNonNull(walletId);
    }

    /**
     * Восстанавливает новый объект из полного упорядоченного префикса истории.
     * Пустая история означает ещё не созданный кошелёк. Хранилище проверяет номера
     * событий; здесь проверяется смысловая целостность. Нет часов, I/O и вызовов decide.
     */
    public static Wallet rehydrate(UUID walletId, List<WalletEvent> events) {
        Wallet wallet = new Wallet(walletId);
        events.forEach(wallet::apply);
        return wallet;
    }

    /**
     * Проверяет новую команду и возвращает один новый факт, не меняя агрегат.
     * expectedVersion проверяется до бизнес-правил; окончательную защиту от гонки
     * обеспечивает CAS в БД. Ошибки входа/состояния представлены WalletException.
     */
    public List<WalletEvent> decide(WalletCommand command) {
        if (command instanceof WalletCommand.CreateWallet c) {
            if (!"RUB".equals(c.currency())) {
                throw failure(INVALID_REQUEST, "Поддерживается только RUB");
            }
            if (exists()) {
                throw failure(WALLET_ALREADY_EXISTS, "Кошелёк уже существует");
            }
            return List.of(new WalletEvent.WalletCreated(c.currency()));
        }
        if (command.expectedVersion() < 1) {
            throw failure(INVALID_REQUEST, "Версия должна быть положительной");
        }
        if (!exists()) {
            throw failure(WALLET_NOT_FOUND, "Кошелёк не найден");
        }
        if (command.expectedVersion() != version) {
            throw failure(VERSION_CONFLICT, "Версия кошелька изменилась");
        }
        if (version == Long.MAX_VALUE) {
            throw failure(VERSION_CONFLICT, "Диапазон версий исчерпан");
        }
        if (status == Status.CLOSED) {
            throw failure(WALLET_CLOSED, "Кошелёк закрыт");
        }
        return switch (command) {
            case WalletCommand.DepositMoney c -> {
                requireAmount(c.amountMinor());
                try {
                    Math.addExact(balanceMinor, c.amountMinor());
                } catch (ArithmeticException e) {
                    throw failure(BALANCE_OVERFLOW, "Переполнение баланса");
                }
                yield List.of(new WalletEvent.MoneyDeposited(c.amountMinor()));
            }
            case WalletCommand.WithdrawMoney c -> {
                requireAmount(c.amountMinor());
                if (balanceMinor < c.amountMinor()) {
                    throw failure(INSUFFICIENT_FUNDS, "Недостаточно средств");
                }
                yield List.of(new WalletEvent.MoneyWithdrawn(c.amountMinor()));
            }
            case WalletCommand.CloseWallet ignored -> {
                if (balanceMinor != 0) {
                    throw failure(NON_ZERO_BALANCE, "Для закрытия нужен нулевой баланс");
                }
                yield List.of(new WalletEvent.WalletClosed());
            }
            case WalletCommand.CreateWallet ignored -> throw new IllegalStateException("Создание обработано выше");
        };
    }

    /**
     * Применяет уже принятый факт: одинаково для новых событий и replay.
     * Не вызывает команды и не проверяет expectedVersion. Проверки ниже защищают
     * целостность фактов (первое создание, допустимые суммы и переходы), а не заново
     * разрешают историческую команду. Побочный эффект ограничен этим объектом.
     */
    public void apply(WalletEvent event) {
        if (version == Long.MAX_VALUE) {
            throw corrupt("Переполнение версии");
        }
        switch (event) {
            case WalletEvent.WalletCreated e -> {
                if (exists() || !"RUB".equals(e.currency())) {
                    throw corrupt("Некорректное создание");
                }
                currency = e.currency();
                status = Status.ACTIVE;
            }
            case WalletEvent.MoneyDeposited e -> {
                requireActiveFact();
                if (e.amountMinor() <= 0) {
                    throw corrupt("Неположительное пополнение");
                }
                try {
                    balanceMinor = Math.addExact(balanceMinor, e.amountMinor());
                } catch (ArithmeticException ex) {
                    throw new CorruptHistoryException("Переполнение в истории", ex);
                }
            }
            case WalletEvent.MoneyWithdrawn e -> {
                requireActiveFact();
                if (e.amountMinor() <= 0 || e.amountMinor() > balanceMinor) {
                    throw corrupt("Некорректное списание");
                }
                balanceMinor -= e.amountMinor();
            }
            case WalletEvent.WalletClosed ignored -> {
                requireActiveFact();
                if (balanceMinor != 0) {
                    throw corrupt("Закрытие с ненулевым остатком");
                }
                status = Status.CLOSED;
            }
        }
        version++;
    }

    public boolean exists() {
        return version > 0;
    }

    public UUID walletId() {
        return walletId;
    }

    public long balanceMinor() {
        return balanceMinor;
    }

    public String currency() {
        return currency;
    }

    public Status status() {
        return status;
    }

    public long version() {
        return version;
    }

    private void requireActiveFact() {
        if (!exists() || status != Status.ACTIVE) {
            throw corrupt("Факт вне активного кошелька");
        }
    }

    private static void requireAmount(long amount) {
        if (amount <= 0) {
            throw failure(INVALID_REQUEST, "Сумма должна быть положительной");
        }
    }

    private static WalletException failure(WalletException.Code code, String message) {
        return new WalletException(code, message);
    }

    private static CorruptHistoryException corrupt(String message) {
        return new CorruptHistoryException(message);
    }

    /** Конечные состояния существующего кошелька. */
    public enum Status {
        ACTIVE, CLOSED
    }
}
