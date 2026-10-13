# А2. Карта системы — C4 Level 2 (Container)

Что было в проекте: диаграмма контейнеров есть дважды — `docs/architecture-diagram.puml`
и mermaid-блок в `docs/architecture.md`. Обе непригодны для планирования тестов:

- все связи нарисованы одинаковыми стрелками — sync и async из-за этого не различимы;
- `docs/architecture-diagram.puml` вообще не показывает RabbitMQ, outbox и publisher confirms;
- состав контейнеров не сверен с `docker-compose.yaml`.

Что сделано: пересобран состав контейнеров по `docker-compose.yaml` и по исходникам,
связи разведены на sync (сплошная линия, HTTP/JDBC) и async (пунктир, AMQP).

## Диаграмма

```mermaid
flowchart TB
    subgraph CLIENTS["Клиенты (вне границы СМП)"]
        POS["POS / ATM<br/><i>эмулятор</i>"]
        MER["Мерчант / эквайрер<br/><i>эмулятор</i>"]
        OPS["Оператор<br/><i>браузер</i>"]
    end

    subgraph EDGE["Точка входа"]
        GW["<b>Gateway</b> :8080<br/>Spring Cloud Gateway MVC<br/>валидация, rate-limit,<br/>circuit breaker, кэш карт"]
    end

    subgraph CORE["Контейнеры приложения"]
        TSIM["<b>Terminal Simulator</b> :8085"]
        MSIM["<b>Merchant / Acquirer</b> :8084"]
        SW["<b>Switch</b> :8082<br/>маршрутизация, fee,<br/>CircuitBreaker + Retry"]
        AU["<b>Authorization</b> :8083<br/>лимиты, баланс,<br/>резервирование"]
        CM["<b>Card Management</b> :8081<br/>CRUD карт, резервы,<br/>outbox"]
        BL["<b>Bin Lookup</b> :8096<br/>справочник BIN"]
        LG["<b>Transaction Logger</b> :8088<br/>журнал + dashboard API"]
        NT["<b>Notification Service</b> :8097<br/>уведомления о событиях карт"]
        DB["<b>Dashboard</b> :3000<br/>React + Vite SPA"]
    end

    subgraph INFRA["Инфраструктура"]
        PG[("<b>PostgreSQL 16</b><br/>:5432")]
        RMQ{{"<b>RabbitMQ 3.13</b><br/>:5672 / UI :15672"}}
    end

    POS -- "HTTP /api/transactions" --> GW
    MER -- "HTTP /api/transactions" --> GW
    OPS -- "HTTP (через Dashboard)" --> DB
    DB -- "HTTP /api/dashboard/**, /api/cards/**" --> GW

    GW -- "HTTP /api/transactions → /api/internal/route" --> SW
    GW -- "HTTP /api/cards/**" --> CM
    GW -- "HTTP /api/dashboard/**, /api/transactions/**" --> LG
    GW -- "HTTP /api/simulator/terminal/**" --> TSIM
    GW -- "HTTP /api/simulator/merchant/**" --> MSIM

    TSIM -- "HTTP POST /api/transactions" --> GW
    MSIM -- "HTTP POST /api/transactions" --> GW

    SW -- "HTTP POST /api/internal/authorize<br/>/api/internal/rollback" --> AU
    SW -- "HTTP GET /api/bin/{bin}" --> BL
    AU -- "HTTP GET /api/cards/{pan}<br/>POST /api/cards/{pan}/reserve" --> CM
    AU -- "HTTP GET /api/bin/{bin}" --> BL

    CM -- "JDBC" --> PG
    AU -- "JDBC" --> PG
    LG -- "JDBC" --> PG
    NT -- "JDBC (JPA)" --> PG

    SW -. "AMQP publish<br/>smp.transactions / transaction.log<br/>publisher confirms (2s)" .-> RMQ
    RMQ -. "AMQP consume<br/>queue transaction-log" .-> LG
    CM -. "AMQP publish (outbox)<br/>smp.card-events / card.*" .-> RMQ
    RMQ -. "AMQP consume<br/>queue card-notifications" .-> NT
```

## Сверка состава с `docker-compose.yaml`

| Контейнер | Хост-порт | Где подтверждается |
|---|---|---|
| `gateway` | 8080 | `services/gateway/src/main/resources/application.yml` |
| `card-management` | 8081 | `services/card-management/.../application.properties` |
| `switch` | 8082 | `services/gateway/.../application.yml` (route URI) |
| `authorization` | 8083 | `services/switch` → `AuthorizationClient` |
| `merchant-acquirer` | 8084 | `services/merchant-acquirer/.../application.yaml` |
| `terminal-simulator` | 8085 | `services/terminal-simulator/.../application.yml` |
| `transaction-logger` | 8088 | `services/gateway/.../application.yml` (route URI) |
| `bin-lookup` | 8096 | `services/bin-lookup/.../application.yml`, health `/actuator/health` |
| `notification-service` | 8097 | `services/notification-service/.../application.yml`, health `/actuator/health` |
| `dashboard` | 3000 | `services/dashboard/vite.config.ts` (`proxy.target = :8080`) |
| `postgres` | 5432 | общий `datasource.url` во всех сервисах |
| `rabbitmq` | 5672 / 15672 | `services/*/.../RabbitMQConfig.java`, `application.yml` |

В ТЗ сказано «11 сервисов + PostgreSQL + RabbitMQ». По `docker-compose.yaml`
прикладных контейнеров 10: `gateway`, `card-management`, `switch`, `authorization`,
`bin-lookup`, `notification-service`, `transaction-logger`, `terminal-simulator`,
`merchant-acquirer`, `dashboard`. Плюс три контейнера observability-профиля
(`prometheus`, `grafana`, `loki`/`promtail`).

## Разметка sync / async

| Тип | Связи | Почему это важно для тестов |
|---|---|---|
| **sync (HTTP)** | Клиент→Gateway, Gateway→{Switch, CM, Logger, симуляторы}, Switch→Authorization, Switch→Bin Lookup, Authorization→{CM, Bin Lookup} | проверяются контрактом и проверкой кода ответа; отказ сразу виден клиенту |
| **sync (JDBC)** | CM, Authorization, Logger, Notification → PostgreSQL | требуют integration-уровня: реальная схема, транзакции, уникальные индексы |
| **async (AMQP)** | Switch→`smp.transactions`→Logger; CM(outbox)→`smp.card-events`→Notification | клиент **не увидит** ошибку доставки; нужны тесты на publisher confirm, DLQ и eventual consistency |

Ключевой вывод для пирамиды: у СМП есть асинхронная вилка внутри обработки
синхронного запроса (Switch публикует лог до возврата ответа). Именно она порождает
сценарий reversal — если подтверждения от брокера нет, одобренная транзакция
откатывается и клиент получает код `96`. См. `a4-sequences.md` и `a5-messaging.md`.

## Проверка по коду

| Утверждение | Подтверждение |
|---|---|
| Gateway — единственная внешняя точка | `services/gateway/.../application.yml`, `spring.cloud.gateway.routes` |
| Синхронные внутренние вызовы | `services/switch/.../AuthorizationClient.java`, `services/authorization/.../CardManagementClient.java`, `services/authorization/.../BinLookupClient.java` |
| Публикация транзакции в очередь | `services/switch/.../LoggerClient.java` → `rabbitTemplate` |
| Потребитель журнала | `services/transaction-logger/.../TransactionLogListener.java` (`@RabbitListener`) |
| Потребитель уведомлений | `services/notification-service/.../listener/CardEventListener.java` (`@RabbitListener`) |
| Публикация карт через outbox | `services/card-management/.../OutboxEventProcessorImpl.java` |
| Топология очередей | `services/switch/.../RabbitMQConfig.java`, `services/card-management/.../RabbitMQConfig.java` |
| Резервные пути Gateway | `TransactionValidationFilter`, `TransactionRateLimitFilter`, `ResponseCachingGatewayFilterFactory`, `CircuitBreakerFilter` |