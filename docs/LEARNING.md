# Шаг 2: Event Sourcing + синхронный CQRS

Ветка `02-cqrs-sync` продолжает готовый кошелёк. Бизнес-команды, события, денежные единицы,
fingerprint и сохранённые ответы шага 1 остаются прежними. Изменились путь текущего чтения
и транзакционная запись: появилась производная таблица `wallet_read_model`.

## Что изменилось по сравнению с шагом 1

| Вопрос | Шаг 1 | Шаг 2 |
|---|---|---|
| Источник истины | wallet_events | wallet_events |
| Состояние для бизнес-решений | Replay событий | Replay событий |
| Текущий GET | Replay | SELECT wallet_read_model |
| Исторический GET | Replay префикса | Replay префикса |
| Прикладные сервисы | Один сервис шага 1 | WalletCommandServiceImpl + WalletQueryServiceImpl |
| Запись | Версия + событие + receipt | Версия + событие + проекция + receipt |
| Сравнение моделей | Не было | Явный GET /comparison, единый снимок |
| Инициализация | Три таблицы Liquibase | Четыре таблицы Liquibase на чистой БД |

CQRS означает разделение моделей команд и запросов. Для команд нужен `Wallet` с правилами,
а для обычного чтения достаточно готовой строки. Разные базы или микросервисы для этого не нужны.
В этой ветке оба пути используют одну PostgreSQL и одно приложение, но разные модели и сервисы.

## Путь команды

```text
Controller → WalletCommandServiceImpl
  → fingerprint + поиск receipt
  → готовый receipt: вернуть прежний ответ, проектор не вызывать
  → иначе TransactionTemplate (READ COMMITTED, REQUIRES_NEW)
      → EventStore.load → Wallet.rehydrate
      → Wallet.decide → Wallet.apply
      → EventStore.append: CAS + INSERT события
      → WalletReadModelProjector.apply: INSERT/UPDATE проекции
      → receipts.insert
    COMMIT
  → INFO об успехе → HTTP-ответ
```

Модель чтения не участвует в проверке достаточности средств. Сервер не доверяет её балансу
для принятия команды, даже если frontend показывает именно этот баланс. Клиент передаёт
expectedVersion; домен проверяет её по событиям, а SQL CAS окончательно защищает запись.

Проектор применяет факт: MoneyDeposited увеличивает копейки, MoneyWithdrawn уменьшает,
WalletCreated вставляет строку, WalletClosed меняет статус. Он не вызывает decide повторно.
Для события v UPDATE допускается только при last_event_version = v-1. Повтор, пропуск или
отсутствующая строка — ошибка целостности, а не повод для upsert. BIGINT и CHECK защищают
от переполнения и недопустимого состояния фактов. Ошибка проектора откатывает всю команду.

Внешний REQUIRES_NEW сохранён: успех возвращается только после собственного commit сценария.
У проектора и receipt нет независимой транзакции. При конкурентном отказе сначала завершается
rollback, затем в отдельной транзакции читается receipt конкурента. Новая версия для
автоматического повторного списания не подбирается.

## Путь чтения

```text
GET /wallets/{id}                → WalletQueryServiceImpl → wallet_read_model
GET /wallets/{id}?atVersion=N    → EventStore.load → replay префикса
GET /wallets/{id}/events         → страница фактов, limit + 1
GET /wallets/{id}/comparison     → один read-only REPEATABLE READ снимок
                                    → EventStore.load → replay
                                    → SELECT wallet_read_model
                                    → сравнение полей
```

У всех путей префикс `/api`. Обычный GET возвращает привычные JSON-поля, но `version` берёт
из `last_event_version`. Отсутствие строки у существующего потока означает
`500 PROJECTION_INTEGRITY_ERROR`, а неизвестный кошелёк — `404 WALLET_NOT_FOUND`.
Никакого незаметного восстановления в GET нет.

Сравнение читает модели независимо. REPEATABLE READ нужен, чтобы между чтениями конкурентный
commit не создал ложное расхождение. Внутри callback нет вызовов сервисов с отдельным
REQUIRES_NEW. При отсутствии проекции возвращаются `readModel: null` и `matches: false`.
Разные баланс, валюта, статус или версия также дают false. Такой диагностический результат
возвращается с HTTP 200; неизвестный кошелёк — с 404.

## Почему после commit нет асинхронного отставания

Событие и проекция фиксируются одной транзакцией PostgreSQL. Другой запрос не увидит
зафиксированное новое событие без соответствующего обновления проекции из этой команды.
Если обновление проекции завершилось ошибкой, событие тоже не фиксируется.

Это не означает, что уже открытая вкладка обновляется сама: UI хранит результат предыдущего
запроса. Разные GET могут относиться к разным моментам, а сравнение согласовано внутри своего
снимка. Polling и искусственная задержка в этом этапе отсутствуют. Асинхронный проектор,
очередь и eventual consistency относятся к будущему этапу, здесь их нет.

## Почему проекция остаётся производной

wallet_read_model содержит результат применения фактов, а не самостоятельные бизнес-данные.
Поэтому её состояние в принципе можно восстановить из истории. Однако отдельная утилита
восстановления в текущий учебный объём не входит: мы запускаемся на чистой БД, а все новые
команды сразу обновляют обе модели одной транзакцией. Отсутствующая проекция существующего
кошелька остаётся ошибкой целостности; GET не скрывает её автоматическим заполнением.

## Запуск на чистой БД

Пользователь самостоятельно создаёт пустую БД. Приложение не удаляет БД и Docker volume.
Для стандартной локальной конфигурации выполните из корня проекта:

```bash
export DB_URL='jdbc:postgresql://127.0.0.1:28742/wallet'
export DB_USERNAME='wallet'
export DB_PASSWORD='wallet'
docker compose up -d --wait postgres
./gradlew bootJar
java -jar build/libs/event-sourcing-wallet-0.1.0.jar
```

Альтернатива запуску JAR: `./gradlew bootRun`. Дополнительных профилей нет.

Liquibase последовательно создаёт event_streams, wallet_events, command_receipts, затем
wallet_read_model. Схема готова до первого запроса. Первая команда CreateWallet создаёт
поток, событие, проекцию и receipt одним commit. Пустой проектор отдельно запускать не нужно.
Повторные старты сохраняют данные и пропускают уже применённые changeset.

В другом терминале используйте Node 24.21.0 из `.nvmrc`:

```bash
cd frontend
nvm use
npm ci
npm start
```

Если версия ещё не установлена через nvm, выполните `nvm install`. Без nvm установите эту
версию Node другим способом. UI — `http://localhost:28743`, API — 28741, PostgreSQL — 28742.
Подробные настройки — [README](../README.md).

## Порядок чтения кода

1. [Wallet](../src/main/java/com/example/wallet/domain/Wallet.java): decide/apply/rehydrate и бизнес-инварианты.
2. [WalletController](../src/main/java/com/example/wallet/controller/WalletController.java): два интерфейса сервиса.
3. [WalletCommandServiceImpl](../src/main/java/com/example/wallet/service/impl/WalletCommandServiceImpl.java): транзакция команды.
4. [JdbcEventStore](../src/main/java/com/example/wallet/repository/jdbc/JdbcEventStore.java): CAS и строгий порядок событий.
5. [WalletReadModelProjector](../src/main/java/com/example/wallet/service/WalletReadModelProjector.java): применение фактов.
6. [JdbcWalletReadModelRepository](../src/main/java/com/example/wallet/repository/jdbc/JdbcWalletReadModelRepository.java): SQL проекции.
7. [WalletQueryServiceImpl](../src/main/java/com/example/wallet/service/impl/WalletQueryServiceImpl.java): GET и единый снимок сравнения.
8. [ModelComparisonComponent](../frontend/src/app/components/model-comparison.component.ts): явная диагностика в UI.

## Ручное изучение через интерфейс

1. Создайте кошелёк. Основная карточка читает готовую проекцию после commit создания.
2. Пополните на 1 000 ₽, спишите 300 ₽. Откройте JSON событий и сопоставьте версии.
3. Нажмите «Обновить сравнение». Два независимо полученных состояния должны показать 700 ₽
   и одинаковые валюту, статус, версию. Сравнение не выполняется при каждом обычном GET.
4. Посмотрите историческую версию 2: там будет 1 000 ₽. Это replay прошлого, а не текущая проекция.
5. Повторите последнюю команду прежней кнопкой. Ответ команды будет исходным, новой версии
   события и повторного изменения проекции не возникнет. Снова запросите сравнение вручную.
6. После обновления текущего состояния старое сравнение сбрасывается. Внешние изменения
   сами не обновляют вкладку; нажмите соответствующие кнопки чтения.

## Разрешённые сборки и фактическая проверка

```bash
./gradlew bootJar
cd frontend
npm run build
```

В этой задаче выполнены bootJar на Java 25.0.3 и production build на доступном Node 24.19.0.
Зафиксированная проектом версия Node остаётся 24.21.0, зависимости не обновлялись.
Миграции и приложение против БД не запускались. Новых тестов нет; тесты,
автоматические HTTP/браузерные сценарии, Gradle test/check/build не выполнялись.
Компиляция не доказывает корректность конкурентного поведения или SQL на реальной БД.
