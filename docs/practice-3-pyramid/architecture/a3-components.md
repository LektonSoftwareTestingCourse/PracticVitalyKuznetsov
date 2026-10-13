# А3. Карта системы — C4 Level 3 (Component)

Что было в проекте: ничего. `docs/architecture-diagram.puml` останавливается на
уровне контейнеров, поэтому не видно, где именно внутри сервиса живут HTTP-клиенты,
JDBC-доступ и публикация в RabbitMQ. Без этого нельзя выбрать уровень теста: непонятно,
что можно проверить на unit-уровне, а что требует поднятого окружения.

Что сделано: разобраны два самых нагруженных контейнера — Authorization и
Card Management.

---

## А3.1 Authorization Service

```mermaid
flowchart TB
    CTRL["<b>AuthControllerImpl</b><br/>REST: /api/internal/authorize, /api/internal/rollback"]

    SVC["<b>AuthServiceImpl</b> (AuthService)<br/>authorize() · rollback()<br/>generateRRN() · generateAuthCode()"]

    CMCL["<b>CardManagementClientImpl</b><br/>HTTP-клиент"]
    BLCL["<b>BinLookupClient</b><br/>HTTP-клиент"]
    LimR["<b>LimitUsageRepository</b><br/>JDBC"]
    NOT["<b>AuthorizationEventNotifierImpl</b><br/>→ AuthorizationEventListener"]
    CLN["<b>CleanupServiceImpl</b><br/>scheduled очистка limit_usage"]
    DEC["<b>DeclineOutcome</b><br/>коды ответов, таблица отказов"]

    CTRL --> SVC
    SVC --> CMCL
    SVC --> BLCL
    SVC --> LimR
    SVC --> DEC
    SVC --> NOT
    CLN --> LimR

    CMCL -- "HTTP GET /api/cards/{pan}<br/>POST …/reserve, …/rollback" --> CMS[("Card Management")]
    BLCL -- "HTTP GET /api/bin/{bin}" --> BL[("Bin Lookup")]
    LimR -- "JDBC: upsert, select,<br/>fetchRrnBlock (SEQ)" --> PG[("PostgreSQL")]

    style CMCL fill:#e8f4ff
    style BLCL fill:#e8f4ff
    style LimR fill:#fff4e8
```

### Процесс `authorize()` — где принимается решение

Порядок проверок в `AuthServiceImpl.authorize()` (каждая ветка отказа = класс
эквивалентности и потенциальный тест-кейс):

| # | Проверка | Исход отказа | `responseCode` |
|---|---|---|---|
| 1 | `cardManagementClient.getCard(pan)` | `CARD_NOT_FOUND` | `14` |
| 1 | сервис CMS недоступен / 5xx | `SERVICE_UNAVAILABLE` | `96` |
| 1 | CMS ответил 402 (Payment Required) | `CARD_BLOCKED` | `05` |
| 2 | `card.status == ACTIVE` | `CARD_INACTIVE` / `CARD_BLOCKED` | `05` |
| 3 | `expiryDate` не в прошлом | `CARD_EXPIRED` | `54` |
| 4 | `amount ≤ availableBalance` | `INSUFFICIENT_FUNDS` | `51` |
| 5 | атомарный `upsertLimitUsage` (дневной и месячный лимит одной SQL-операцией) | `EXCEEDS_AMOUNT_LIMIT` | `61` |
| 5 | ошибка БД на лимитах | `DB_UNAVAILABLE` | `96` |
| 6 | `cardManagementClient.reserve()` | `RESERVATION_FAILED` / `INSUFFICIENT_FUNDS` | `96` / `51` |
| — | любое непойманное исключение | `UNKNOWN_REASON` | `05` |
| ok | все проверки пройдены | RRN (12 цифр) + authCode (6 символов) | `00` |

Порядок важен: баланс проверяется раньше лимитов, а дневной и месячный лимиты
проверяются одной операцией `upsertLimitUsage` (возврат `0` = превышение), а не двумя
независимыми ветками. Дублирующий `DuplicateKeyException`/`ConstraintViolationException`
обрабатывается повторным вызовом; повторная неудача → `DB_UNAVAILABLE` (`96`).

Проверка BIN (`binLookupClient.getIssuerId`) — не блокирующая: при недоступности
Bin Lookup сервис продолжает работу (graceful degradation), `issuerId` не обогащается.

### Процесс `rollback()`

`cardManagementClient.rollback(request)` → отображение исключений CMS в исходы:
`CardNotFoundException`/`InvalidRollbackRequestException` → `TRANSACTION_NOT_FOUND` (`14`),
`ServiceUnavailable`/`ResourceAccess`/`InternalCardManager` → `SERVICE_UNAVAILABLE` (`96`),
`RollbackConflictException` → `ALREADY_ROLLED_BACK` (`05`), `RollbackFailureException` →
`ROLLBACK_FAILED` (`96`), прочее → `UNKNOWN_REASON` (`05`).

---

## А3.2 Card Management Service

```mermaid
flowchart TB
    CTRLC["<b>CardController</b><br/>REST: /api/cards"]

    SVCC["<b>CardServiceImpl</b> (CardService)<br/>createCard(s) · getCard(s) · patchCard<br/>deleteCard · reserve · rollback"]
    GEN["<b>CardGeneratorService</b><br/>+ PanGenerator + LuhnValidator"]
    BINI["<b>BinIssuerServiceImpl</b>"]
    TRX["<b>TransactionRunnerImpl</b><br/>границы транзакций"]

    OCR["<b>CardRepository</b> / <b>ReservationRepository</b> /<br/><b>ReservationRollbackRepository</b> / <b>OutboxRepository</b><br/>(adapter → Spring Data JPA)"]
    OUT["<b>OutboxEventProcessorImpl</b><br/>publish событий"]
    RET["<b>RetryServiceImpl</b>"]
    MET["<b>CardMetricsEventListener</b><br/>Micrometer: cards.operations, cards.created"]

    CTRLC --> SVCC
    SVCC --> GEN
    SVCC --> BINI
    SVCC --> TRX
    TRX --> OCR
    SVCC --> OUT
    OUT --> RET
    OCR --> MET

    OCR -- "JDBC/JPA" --> PG[("PostgreSQL<br/>card, reservation,<br/>reservation_rollback, outbox_event")]
    OUT -- "AMQP convertAndSend<br/>exchange smp.card-events<br/>routing key card.&lt;EventName&gt;" --> RMQ{{"RabbitMQ"}}

    style OUT fill:#e8ffe8
    style OCR fill:#fff4e8
```

### Где живёт асинхронность (outbox)

`CardServiceImpl` не публикует в RabbitMQ напрямую. Он пишет событие в таблицу
`outbox_event` внутри той же транзакции, что и изменение карты. Дальше
`OutboxEventProcessorImpl.processSingleEvent` забирает событие и вызывает
`rabbitTemplate.convertAndSend(CARD_EVENTS_EXCHANGE, "card." + event.getClass().getSimpleName(), event)`.
При ошибке срабатывает `handleFail` → `RetryServiceImpl` (до `maxRetryCount` попыток),
после чего событие помечается как `failed()`.

Тестовое следствие: успешное создание карты (`HTTP 201`) гарантирует запись в
`outbox_event`, но не гарантирует доставку в Notification Service. Это два разных
проверяемых факта: первый — `api`/`integration`, второй — `e2e` с ожиданием
событийной согласованности.

---

## Проверка по коду

| Компонент | Файл |
|---|---|
| REST-контроллер авторизации | `services/authorization/.../controller/AuthControllerImpl.java` |
| Логика авторизации и отката | `services/authorization/.../services/AuthServiceImpl.java` |
| Таблица исходов и кодов | `services/authorization/.../constants/DeclineOutcome.java` |
| HTTP-клиент CMS | `services/authorization/.../client/CardManagementClientImpl.java` |
| HTTP-клиент Bin Lookup | `services/authorization/.../client/BinLookupClient.java` |
| Доступ к лимитам (JDBC) | `services/authorization/.../repositories/LimitUsageRepository.java` |
| События авторизации | `services/authorization/.../listeners/AuthorizationEventListener.java`, `AuthorizationLogEventListenerImpl.java` |
| REST-контроллер карт | `services/card-management/.../controller/CardController.java` |
| Логика карт, резервов | `services/card-management/.../services/CardServiceImpl.java` |
| Публикация outbox | `services/card-management/.../services/OutboxEventProcessorImpl.java` |
| Повторы публикации | `services/card-management/.../services/retries/RetryServiceImpl.java` |
| Репозитории | `services/card-management/.../repositories/*Repository*.java` |
| Метрики | `services/card-management/.../metrics/CardMetricsEventListener.java` |
