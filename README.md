# event-sourcing-wallet — `04-axon`

Учебный переход с собственной реализации этапа 03 на **открытый Axon Framework 5.3.2**.
Одно Spring Boot приложение и одна PostgreSQL. Axon доставляет команды, восстанавливает Wallet,
сохраняет события и конкурентную версию, выполняет асинхронную проекцию и хранит её token.
Приложение сохраняет бизнес-правила, expectedVersion, fingerprint, receipts, HTTP API и Angular UI.

| Компонент | Версия |
|---|---|
| Java | 25 |
| Spring Boot | 4.0.8 |
| Gradle | 9.5.1 |
| Axon Framework / Spring extension | 5.3.2 |
| PostgreSQL | 17.11-bookworm |
| Angular / Node.js | 22.1.7 / 24.21.0 |

Используется штатный `AggregateBasedJpaEventStorageEngine`, **не DCB**. JPA хранит события,
не текущий баланс Wallet. Read model и receipts работают через Spring JDBC, позиции — через
`JdbcTokenStore`. Axon Server и коммерческий PostgreSQL-модуль не подключены.
Версии и источники: [docs/axon.md](docs/axon.md).

## Чистая БД и запуск

Порты: API 28741, PostgreSQL 28742, Angular 28743. Они совпадают с этапом 03: сначала остановите
его backend, frontend и PostgreSQL. В checkout этапа 03 это `docker compose stop postgres`.
Новая ветка использует Compose project `event-sourcing-wallet-04-axon` и volume
`wallet_axon_postgres_data` (полное имя `event-sourcing-wallet-04-axon_wallet_axon_postgres_data`).
DB_URL по умолчанию `jdbc:postgresql://127.0.0.1:28742/wallet`, DB_USERNAME/DB_PASSWORD — `wallet`.
Не задавайте URL базы старого этапа. Перенос данных не поддерживается.

```bash
docker compose up -d --wait postgres
./gradlew bootRun
```

Liquibase создаёт полную схему: `001-axon.sql` — события и tokens, `002-wallet.sql` — receipts
и read model. Hibernate не создаёт и не изменяет таблицы. Приложение не очищает данные.

```bash
cd frontend
npm ci
npm start
```

Сборки без тестов:

```bash
./gradlew bootJar -x test
cd frontend
npm run build
```

Остановка БД без удаления данных: `docker compose stop postgres`.
Необязательный ручной сброс **в checkout этапа 04**, уничтожающий все его события, tokens,
receipts и проекцию (не выполнять для сохранения истории):

```bash
docker compose down -v
docker compose up -d --wait postgres
```

## Транзакции

Команда: HTTP → CommandGateway → восстановление Wallet → decide → WalletFact → применение
факта → JDBC receipt → JPA flush → общий commit → ответ. Ошибка откатывает receipt и событие.
Проекция: streaming processor → применение WalletFact → JDBC read model + JDBC token → отдельный commit.
Один JpaTransactionManager и DataSource связывают JPA/JDBC внутри Axon ProcessingContext.
Внешняя транзакция вокруг gateway для этого не используется.

Версия кошелька начинается с 1, sequence Axon — с 0. Tracking token не показывает версию кошелька.
Receipt обеспечивает постоянную идемпотентность и возвращает прежний результат команды.
Read model может отставать. Event Sourcing, CQRS и eventual consistency — разные понятия.

## API и UI

Бизнес-пути и JSON сохранены: создание, deposits, withdrawals, close, GET, atVersion, events,
comparison. Обычный GET читает read model: отсутствие первой проекции — 409 PROJECTION_NOT_READY,
неизвестный UUID — 404 WALLET_NOT_FOUND. История читается через Axon, второй копии событий нет.
Comparison сначала читает проекцию, затем проверяет её по префиксу событий на той же бизнес-версии.
Это не единый RR-снимок: pendingEvents — разница наблюдаемых бизнес-версий.

GET `/api/projection-handler`, POST `/pause` и `/resume` относительно этого пути управляют
реальным processor текущего экземпляра. `PAUSE_REQUESTED` означает ожидание завершения текущей
обработки; `PAUSED` — завершённый shutdown. Resume не сбрасывает token. После рестарта processor
запускается автоматически. Один segment обслуживает все кошельки: ошибка может задержать их всех.
Неуспешный shutdown не подтверждает паузу и отображается как ошибка с возможностью повторного
управления. При IDLE интерфейс предлагает возобновить обработчик.
Интервал обнаружения событий `axon.eventstorage.jpa.polling-interval=3000` мс, размер порции 50.
Внутрипроцессное уведомление Axon может доставить события раньше этого интервала.

UI показывает ответ команды отдельно от read model. Известные версии хранятся по UUID;
неизвестный результат блокирует новые команды до повтора исходного запроса. Polling пропускает
занятый ресурс, ручное обновление оставляет максимум один повтор; завершённые запросы освобождаются.
Это относится и к статусу обработчика без выбранного кошелька.

Ручной сценарий:

1. Создать кошелёк, дождаться совпадения моделей.
2. Приостановить processor, дождаться PAUSED.
3. Пополнить кошелёк: ответ успешен, событие видно в истории, read model остаётся прежней.
4. Повторить запрос с прежним ключом: нового события и версии не появляется.
5. Возобновить processor, дождаться сходимости.
6. Для отдельного опыта остановить backend с накопленными событиями и запустить снова:
   processor продолжает с сохранённого token; флаг паузы не сохраняется.

Сходимость требует работающего processor и устранения ошибок. Сборка не доказывает runtime-гарантии.
Тесты и автоматические сценарии не создавались и не запускались; миграции и приложение в рамках
реализации не запускались. Административного reset/rebuild/backfill нет.

## Документы

[Axon и транзакции](docs/axon.md), [архитектура](docs/architecture.md),
[БД](docs/database.md), [API](docs/api-guidelines.md), [frontend](docs/frontend.md),
[учебный маршрут](docs/LEARNING.md), [спецификация](docs/PROJECT.md).
