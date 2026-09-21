# Архитектура этапа 03

Одно приложение и одна PostgreSQL содержат write side и read side. Event Sourcing хранит
факты в `wallet_events`; CQRS разделяет командный и query-сервисы; eventual consistency
возникает потому, что read model обновляет отдельный polling-обработчик.

## Потоки

Команда:

```text
HTTP → WalletCommandServiceImpl
     → load/replay Wallet → decide/apply
     → CAS current_version + INSERT wallet_events + INSERT command_receipt
     → commit → HTTP-ответ write side
```

Обработчик:

```text
@Scheduled polling → список projection_positions
  → отдельная транзакция на wallet_id
  → SELECT position FOR UPDATE
  → wallet_events после позиции, ASC, ограниченная порция
  → WalletReadModelProjector.apply
  → UPDATE wallet_read_model + UPDATE projection_positions → commit
```

Ошибка порции откатывает проекцию и позицию. Следующий цикл повторяет её, остальные кошельки
обрабатываются независимо. После перезапуска обработчик продолжает с сохранённых позиций.

| Пакет | Ответственность |
|---|---|
| `domain` | Wallet, команды и события без Spring/JDBC |
| `service` | интерфейсы сервисов, projector и техническое управление |
| `service.impl` | command/query service и AsyncProjectionHandler |
| `repository` / `repository.jdbc` | контракты и параметризованный SQL |
| `controller` | Wallet API и endpoints управления обработчиком |
| `exception` | доменные и инфраструктурные ошибки, ProblemDetail |

`WalletReadModelProjector` применяет уже принятые факты и не вызывает `decide`. Его проверки
контролируют версии, переполнение и ограничения таблицы, но не повторяют бизнес-решения Wallet.

Обычный GET делает только SELECT read model. Если поток существует, но строки ещё нет, ответ
`409 PROJECTION_NOT_READY`; replay и ожидание запрещены. Исторический GET, история и comparison
читают Event Store. Comparison выполняет replay и SELECT read model в одном read-only
`REPEATABLE READ` снимке. Проекция может быть `null`, её версия тогда 0, а pending равен
версии потока.

Пауза (`/api/projection-handler/pause`) относится только к текущему экземпляру. Это учебный
контроль, а не распределённая блокировка.
