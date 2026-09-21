# REST API

Контроллеры находятся в controller, сервисы — за интерфейсами service.
Командный API сохраняет прежние пути, JSON и Idempotency-Key.

| Метод | Путь | Результат |
|---|---|---|
| PUT | /api/wallets/{id} | создать кошелёк, 201 |
| POST | /api/wallets/{id}/deposits | команда пополнения, 200 |
| POST | /api/wallets/{id}/withdrawals | команда списания, 200 |
| POST | /api/wallets/{id}/close | команда закрытия, 200 |
| GET | /api/wallets/{id} | read model, возможно старая |
| GET | /api/wallets/{id}?atVersion=N | replay исторической версии |
| GET | /api/wallets/{id}/events | страница Event Store |
| GET | /api/wallets/{id}/comparison | replay/read model в одном RR-снимке |
| GET | /api/projection-handler | статус обработчика |
| POST | /api/projection-handler/pause | пауза текущего экземпляра |
| POST | /api/projection-handler/resume | возобновление |

Обычный GET не выполняет replay и не ждёт polling. Если поток существует, но read model ещё
не создана, возвращается 409 с code=PROJECTION_NOT_READY. Отсутствующий поток даёт 404
WALLET_NOT_FOUND. При отставании возвращается фактический last_event_version.

Comparison возвращает две модели и метаданные:

    {
      "eventState": {"walletId":"…","balanceMinor":1000,"currency":"RUB","status":"ACTIVE","version":2},
      "readModel": {"walletId":"…","balanceMinor":0,"currency":"RUB","status":"ACTIVE","version":1},
      "streamVersion": 2, "projectionVersion": 1, "pendingEvents": 1,
      "status": "LAGGING", "matches": false
    }

Одинаковая версия с разными состояниями и версия проекции выше потока дают
500 PROJECTION_INTEGRITY_ERROR. Отставание — ожидаемо и не маскируется под повреждение.
История использует afterVersion и limit 1–500; её пагинация не ограничивает replay.

Ошибки возвращаются как application/problem+json с полем code. Команды проверяются
Wallet, receipt повторяется без нового события, SQL и stack trace клиенту не выдаются.
