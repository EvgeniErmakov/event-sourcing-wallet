# event-sourcing-wallet

Учебный сервис кошельков на Java 25 с собственной реализацией Event Sourcing.
Реализованы создание, пополнение, списание, закрытие, чтение текущего и исторического
состояния, история событий, optimistic locking и постоянная идемпотентность.
Тестов и тестовых зависимостей нет по запросу пользователя.

Документы: [спецификация и статус](docs/PROJECT.md),
[исходные полные требования](PROJECT.md), [правила разработки](AGENTS.md).
Отдельных файлов промптов в исходном репозитории нет; существующие документы сохранены.

## Версии и требования

| Компонент | Зафиксированная версия |
|---|---|
| Java | 25, toolchain и release 25, без preview |
| Spring Boot и BOM | 4.0.8 |
| Gradle Wrapper | 9.5.1, бинарный дистрибутив с SHA-256 |
| PostgreSQL | 17.11, образ `postgres:17.11-bookworm` |

Нужны JDK 25 (не только JRE), `JAVA_HOME` и `PATH`, указывающие на этот JDK,
Docker Engine/Desktop с Compose v2, свободные порты 5432 и 8080.
Для первой сборки и загрузки образа нужен доступ к интернету.
Глобально устанавливать Gradle не требуется. Исходники и JavaDoc используют UTF-8.

Совместимость проверена по [требованиям Boot 4.0](https://docs.spring.io/spring-boot/4.0/system-requirements.html)
и [матрице Gradle 9.5.1](https://docs.gradle.org/9.5.1/userguide/compatibility.html):
Boot поддерживает Java 25 и Gradle 9.x; Gradle запускается на Java 25 начиная с 9.1.0.
Названия зависимостей взяты из [списка starters Boot 4.0](https://docs.spring.io/spring-boot/4.0/reference/using/build-systems.html):
`spring-boot-starter-webmvc`, `spring-boot-starter-validation`,
`spring-boot-starter-jdbc`, `spring-boot-starter-liquibase`, драйвер `org.postgresql:postgresql`.
Версии зависимостей согласованы через [BOM Boot средствами Gradle platform](https://docs.spring.io/spring-boot/4.0/gradle-plugin/managing-dependencies.html),
привязанный к версии плагина Boot. Динамических версий и SNAPSHOT нет.
PostgreSQL выбран из [стабильных выпусков](https://www.postgresql.org/docs/release/17.11/).

## Локальный запуск

Из корня репозитория:

```bash
java -version
docker compose up -d --wait postgres
./gradlew bootJar
./gradlew bootRun
```

В Windows используйте `gradlew.bat`. Вместо `bootRun` можно запустить собранный архив:

```bash
java -jar build/libs/event-sourcing-wallet-0.1.0.jar
```

Приложение слушает порт 8080, API находится под `/api/wallets`.
Liquibase применяет три changeset из `001-wallet.sql` через YAML master changelog.
Это единственный механизм создания схемы; basic SQL initialization отключена (`mode: never`).
Для `bootJar` работающая БД не нужна; для запуска приложения PostgreSQL необходима.

## Настройки окружения

| Переменная | Локальное значение по умолчанию |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/wallet` |
| `DB_USERNAME` | `wallet` |
| `DB_PASSWORD` | `wallet` |

База в Compose называется `wallet`; пользователь и пароль по умолчанию также `wallet`.
Эти общедоступные значения предназначены только для локального обучения.
Порт PostgreSQL опубликован на `127.0.0.1:5432`, данные находятся в именованном volume.

При необходимости скопируйте `.env.example` в `.env` и измените настройки.
Compose автоматически читает `.env` для подстановки переменных; `DB_URL` нужен приложению,
а Compose использует `DB_USERNAME` и `DB_PASSWORD` для инициализации PostgreSQL.
`bootRun` и `java -jar` автоматически `.env` не читают. Без экспорта они используют
локальные defaults из `application.yaml`; для своих значений экспортируйте переменные в shell:

```bash
export DB_URL='jdbc:postgresql://localhost:5432/wallet'
export DB_USERNAME='wallet'
export DB_PASSWORD='wallet'
./gradlew bootRun
```

Изменение переменных Compose не меняет учётные данные уже инициализированного volume.
Локальные `.env` и секреты исключены из Git; `.env.example` содержит только учебные значения.

Остановите приложение через Ctrl+C, а БД — с сохранением данных:

```bash
docker compose stop postgres
```

Не удаляйте volume, если хотите сохранить данные.

## Как изучать код

1. [WalletCommand](src/main/java/com/example/wallet/domain/command/WalletCommand.java) и
   [WalletEvent](src/main/java/com/example/wallet/domain/event/WalletEvent.java) — намерения и факты.
2. [Wallet](src/main/java/com/example/wallet/domain/Wallet.java) — `decide`, `apply`, `rehydrate`.
   `decide` проверяет новую команду, не меняя агрегат; `apply` меняет состояние фактом;
   `rehydrate` создаёт новый объект и последовательно применяет прошлые факты.
3. [WalletService](src/main/java/com/example/wallet/service/WalletService.java) — прикладной контракт;
   [WalletServiceImpl](src/main/java/com/example/wallet/service/impl/WalletServiceImpl.java) — полный сценарий,
   commit до ответа, rollback до повторного чтения receipt, GET через replay.
4. [EventStore](src/main/java/com/example/wallet/repository/EventStore.java) и
   [JdbcEventStore](src/main/java/com/example/wallet/repository/jdbc/JdbcEventStore.java) — граница
   хранения, SQL одного снимка, проверка непрерывности и атомарный CAS.
5. [CommandFingerprint](src/main/java/com/example/wallet/service/CommandFingerprint.java) и
   [JdbcCommandReceiptRepository](src/main/java/com/example/wallet/repository/jdbc/JdbcCommandReceiptRepository.java)
   — каноническое содержание команды и постоянный первоначальный ответ.
6. [EventSerializer](src/main/java/com/example/wallet/serialization/EventSerializer.java) — явный
   реестр имён, schemaVersion=1 и строгая проверка payload. Используется Jackson 3 из BOM Boot 4.
7. [WalletController](src/main/java/com/example/wallet/controller/WalletController.java),
   [MoneyRequestDto](src/main/java/com/example/wallet/dto/request/MoneyRequestDto.java),
   [ApiExceptionHandler](src/main/java/com/example/wallet/exception/api/ApiExceptionHandler.java) — REST,
   DTO/валидация и ProblemDetail. [UuidWebConfiguration](src/main/java/com/example/wallet/config/UuidWebConfiguration.java)
   отвергает сокращённые UUID; [WalletConfiguration](src/main/java/com/example/wallet/config/WalletConfiguration.java)
   задаёт строгий JSON и внедряемый Clock.
8. [Миграции](src/main/resources/db/changelog/001-wallet.sql) — таблицы, ограничения и комментарии PostgreSQL.

Точка запуска — [WalletApplication](src/main/java/com/example/wallet/WalletApplication.java).
Исходный `src/Main.java` сохранён вне стандартного source set Gradle.
Домен не зависит от Spring, JDBC, Jackson или HTTP. `WalletState` — неизменяемый результат
сценария, не отдельная проекция или самостоятельно обновляемое хранилище баланса.

## Структура после рефакторинга

Все пакеты расположены под `com.example.wallet`:

| Пакет | Ответственность |
|---|---|
| `controller` | REST и зависимость от интерфейса WalletService |
| `dto.request`, `dto.response` | HTTP records с прежними JSON-полями |
| `service`, `service.impl` | Контракт сценариев и реализация транзакционной границы |
| `service.model` | WalletState, CommandReceipt, StoredEvent и EventPage |
| `domain`, `domain.command`, `domain.event` | Агрегат, намерения и факты |
| `repository`, `repository.jdbc` | Контракты хранения и параметризованный JDBC |
| `serialization` | Реестр стабильных имён и JSON событий |
| `exception.domain`, `exception.api` | Чистые Java-исключения и ProblemDetail |
| `config` | Clock, строгий JSON и преобразование UUID |

[WalletResponseDto](src/main/java/com/example/wallet/dto/response/WalletResponseDto.java) и
[EventPageResponseDto](src/main/java/com/example/wallet/dto/response/EventPageResponseDto.java)
отображают готовые результаты сервиса для HTTP. Репозитории не используют HTTP DTO:
JSON receipt по-прежнему кодирует WalletState с прежними полями.
CorruptHistoryException остаётся чистым Java-исключением в `exception.domain`, поскольку
его также выбрасывает Wallet при обнаружении недопустимой истории.

Контракт receipt — `find`/`insert`. Отсутствие результата передаётся через Optional без
промежуточного null. После отказа write.execute уже завершил rollback; повторный findReceipt
открывает новую транзакцию чтения. Запись receipt остаётся внутри транзакции события.
INFO о выполненной команде пишется после commit, повтор receipt отмечается только на DEBUG.

При рефакторинге схема не менялась. Исторические файлы
`db/changelog/db.changelog-master.yaml` и `db/changelog/001-wallet.sql` сохранены вместе
с путями, порядком и содержимым changeset. Следующие реальные изменения схемы оформляются
по [database.md](docs/database.md); фиктивной миграции для переноса Java-классов нет.

## События, таблицы и транзакция

| Таблица | Назначение |
|---|---|
| `event_streams` | UUID и техническая `current_version` для CAS; без баланса и статуса |
| `wallet_events` | Неизменяемые факты с уникальным `(stream_id, stream_version)` |
| `command_receipts` | Глобальный ключ, fingerprint, исходные статус/тело успешного ответа |

Источник истины — только последовательность `wallet_events`. Таблица балансов с журналом
операций не дала бы такого свойства: чтение баланса обходило бы восстановление из фактов.
Здесь каждый GET читает события из PostgreSQL. События упорядочены по `stream_version`,
начиная с 1, а не по времени; одинаковые времена допустимы.

`WalletCreated` содержит `currency`, `MoneyDeposited`/`MoneyWithdrawn` — `amountMinor`,
`WalletClosed` — `{}`. Envelope хранит идентификаторы, версию потока и время.
`schema_version` описывает формат payload, а `stream_version` — позицию в истории.
Имена типов заданы явно; неизвестный тип/версия или повреждённые данные приводят к 500.
Replay не читает часы, не генерирует UUID, не запускает команды и не обращается к сети/БД:
он работает с уже загруженным списком фактов. Проверки `apply` защищают целостность истории,
а правила принятия новых команд находятся в `decide`.

Команда выполняется в `TransactionTemplate` с `READ COMMITTED` и `REQUIRES_NEW`:

1. По `Idempotency-Key` читается receipt. Совпавшее содержание сразу возвращает прежний ответ;
   другое содержание даёт `409 IDEMPOTENCY_KEY_REUSED`.
2. В транзакции загружается полный поток одним SELECT. LEFT JOIN технической версии
   позволяет обнаружить пустой/повреждённый поток в том же снимке данных.
3. Replay восстанавливает Wallet. `decide` проверяет expectedVersion и бизнес-правила;
   полученный факт применяется через `apply` к этому временному объекту.
4. Для создания вставляется поток с версией 0. Для всех команд выполняется
   `UPDATE event_streams SET current_version = current_version + 1
   WHERE stream_id = :id AND current_version = :expected`.
5. Вставляются событие и receipt. После commit сервис возвращает успех. При ошибке
   все изменения откатываются, временный Wallet отбрасывается.

CAS атомарен в PostgreSQL: если два списания прочитали одну версию, только одно изменит
её. Второй запрос получает конфликт; автоматического повторного списания нет.
UNIQUE версии дополнительно защищает позицию факта и обслуживает упорядоченное чтение.
Приложение не выполняет UPDATE/DELETE для сохранённых событий.

Для ожидаемых коллизий используется адресный `ON CONFLICT ON CONSTRAINT ... DO NOTHING`.
Ноль вставленных строк превращается в отказ, который откатывает всю транзакцию. Лишь после
rollback сервис читает receipt в новой транзакции. Так обрабатываются дубли создания,
CAS-конфликт, общий command_id для разных кошельков и отказы по уже изменившемуся состоянию.
Произвольная SQL-ошибка не превращается в 409 и не позволяет продолжить прерванную транзакцию.
Если конкурент ещё не зафиксировал результат на момент повторного SELECT, receipt не виден:
возвращается исходный отказ; клиент может повторить ту же команду с тем же ключом.

Fingerprint включает явное имя команды, нормализованный UUID, все бизнес-поля и expectedVersion.
Порядок JSON-полей и пробелы не влияют на него. Изменение ожидаемой версии требует нового ключа.
Ошибки не сохраняются в receipts. GET никогда не получает текущий баланс из receipt.

## API и ошибки

Деньги — `long` в копейках: `100000` = 1 000 ₽, валюта — `RUB`.
Сумма положительная, переполнение запрещено. Закрыть можно только активный кошелёк с нулём.
Для каждой изменяющей операции нужен заголовок `Idempotency-Key` в стандартном формате UUID.

| Метод | Путь | Результат |
|---|---|---|
| PUT | `/api/wallets/{walletId}` | Создание, 201, версия 1 |
| POST | `/api/wallets/{walletId}/deposits` | Пополнение, 200 |
| POST | `/api/wallets/{walletId}/withdrawals` | Списание, 200 |
| POST | `/api/wallets/{walletId}/close` | Закрытие, 200 |
| GET | `/api/wallets/{walletId}` | Текущее состояние через полный replay |
| GET | `/api/wallets/{walletId}?atVersion=2` | Состояние после факта №2 |
| GET | `/api/wallets/{walletId}/events?afterVersion=0&limit=100` | История по версии |

Успешный ответ содержит `walletId`, `balanceMinor`, `currency`, `status`, `version`.
История возвращает `{items, nextAfterVersion, hasMore}`. В каждом item:
`eventId`, `walletId`, `streamVersion`, `eventType`, `schemaVersion`, `payload`, `occurredAt`, `commandId`.
`limit` — от 1 до 500 (по умолчанию 100), `afterVersion` — от 0. Читается limit+1 строк;
пустая страница сохраняет входной курсор. Несуществующий кошелёк даёт 404, существующий
без более новых событий — пустую страницу. Пагинация не ограничивает загрузку для replay.

Ошибки имеют Content-Type `application/problem+json`, стандартные поля ProblemDetail и `code`:

| HTTP | Коды |
|---|---|
| 400 | `INVALID_REQUEST`: JSON, UUID, заголовки, параметры, валюта, сумма или версия |
| 404 | `WALLET_NOT_FOUND`, `VERSION_NOT_FOUND` |
| 409 | `WALLET_ALREADY_EXISTS`, `VERSION_CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, `WALLET_CLOSED`, `INSUFFICIENT_FUNDS`, `NON_ZERO_BALANCE`, `BALANCE_OVERFLOW` |
| 500 | `CORRUPT_HISTORY`, `INTERNAL_ERROR` |

Для стандартных ошибок маршрутизации также используются `RESOURCE_NOT_FOUND`,
`METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`.
Дробные числа (в том числе `1.0`), строковые числа, пропущенные обязательные поля и
неизвестные JSON-поля отвергаются. Стек исключения и SQL остаются только в серверном логе.

## Примеры для самостоятельного изучения

Эти команды приведены только как документация и автоматически не выполнялись.
Используйте новый walletId и новые ключи для самостоятельного повторения всей последовательности.

```bash
base='http://localhost:8080/api/wallets'
wallet='11111111-1111-4111-8111-111111111111'

# 201: ACTIVE, 0 копеек, версия 1
curl -i -X PUT "$base/$wallet" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 22222222-2222-4222-8222-222222222221' \
  -d '{"currency":"RUB"}'

# 200: 100000 копеек, версия 2
curl -i -X POST "$base/$wallet/deposits" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 22222222-2222-4222-8222-222222222222' \
  -d '{"amountMinor":100000,"expectedVersion":1}'

# 200: 80000 копеек, версия 3
curl -i -X POST "$base/$wallet/withdrawals" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 22222222-2222-4222-8222-222222222223' \
  -d '{"amountMinor":20000,"expectedVersion":2}'

curl -i "$base/$wallet"
curl -i "$base/$wallet/events?afterVersion=0&limit=2"
curl -i "$base/$wallet/events?afterVersion=2&limit=2"
curl -i "$base/$wallet?atVersion=2"

# Повтор пополнения после списания: исходные 100000 и версия 2, без нового события
curl -i -X POST "$base/$wallet/deposits" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 22222222-2222-4222-8222-222222222222' \
  -d '{"expectedVersion":1,"amountMinor":100000}'

# То же значение ключа, другая сумма: 409 IDEMPOTENCY_KEY_REUSED
curl -i -X POST "$base/$wallet/deposits" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 22222222-2222-4222-8222-222222222222' \
  -d '{"amountMinor":100001,"expectedVersion":1}'

# Обнуление, версия 4; затем закрытие, версия 5
curl -i -X POST "$base/$wallet/withdrawals" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 22222222-2222-4222-8222-222222222224' \
  -d '{"amountMinor":80000,"expectedVersion":3}'
curl -i -X POST "$base/$wallet/close" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 22222222-2222-4222-8222-222222222225' \
  -d '{"expectedVersion":4}'
```

Повтор закрытия с тем же ключом и тем же телом вернёт первоначальный успех.
Новый ключ с текущей версией 5 даст `WALLET_CLOSED`; устаревшая версия сначала даст `VERSION_CONFLICT`.
`atVersion=0` даёт 400, версия выше последней — `404 VERSION_NOT_FOUND`.

Откройте psql самостоятельно командой `docker compose exec postgres psql -U wallet -d wallet`.
Запросы ниже только читают данные:

```sql
SELECT stream_version, event_type, schema_version, payload, occurred_at, command_id
FROM wallet_events
WHERE stream_id = '11111111-1111-4111-8111-111111111111'::uuid
ORDER BY stream_version ASC;

SELECT stream_id, current_version FROM event_streams
WHERE stream_id = '11111111-1111-4111-8111-111111111111'::uuid;

SELECT command_id, request_fingerprint, response_status, response_body
FROM command_receipts
WHERE command_id = '22222222-2222-4222-8222-222222222222'::uuid;
```

## Границы первой версии и фактическая проверка

Полный replay стоит O(N) по числу событий кошелька; исторический GET также загружает
полный поток и восстанавливает нужный префикс. Это осознанное учебное ограничение.
Нет snapshots, проекций, очередей, кэша, переводов, авторизации, UI или upcasters.
Миграции форматов событий и оптимизация replay оставлены будущим этапам.

На этапе первоначальной реализации были выполнены компиляция и сборка `./gradlew bootJar` на JDK 25.0.3;
обычный запуск собранного JAR с PostgreSQL 17.11, три успешных changeset Liquibase
и запуск Tomcat на 8080. После этого приложение и контейнер остановлены, volume сохранён.
На предыдущем этапе запуск блокировала сеть Docker; теперь для запуска использован временный
Compose override вне репозитория: host network, PostgreSQL на `127.0.0.1:15433`, соответствующий
`DB_URL`. Основной `compose.yaml` сохраняет заданный порт 5432; он занят в текущем окружении.
Для стандартного запуска освободите этот порт либо настройте собственный локальный override.

Тесты по запросу пользователя отсутствуют. HTTP-сценарии, приведённые curl/SQL,
задачи `test`, `check` и полный `build` не выполнялись. Сборка и старт с миграциями
не подтверждают поведение API, гонок, идемпотентности и replay; автоматическая
проверка поведения остаётся за рамками этой работы.


При текущем рефакторинге выполнена сборка `./gradlew bootJar`, просмотр импортов,
локальных ссылок, транзакционных связей и неизменности миграций. Ошибок инвариантов
Event Sourcing при чтении кода не обнаружено; бизнес-поведение не изменялось.
Запуск приложения и обращение к БД в рамках рефакторинга не выполнялись.
Тесты по запросу пользователя не создавались и не запускались. Конкурентное поведение
и HTTP-контракт проверялись только чтением кода, без выполнения сценариев.
