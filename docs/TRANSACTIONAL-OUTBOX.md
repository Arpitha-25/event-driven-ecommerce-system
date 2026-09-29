# Transactional Outbox (order-service)

This document explains what was added to order-service so that order events are never lost, how it works, and how it was tested. It builds on the order → inventory saga described in [ORDER-INVENTORY-SAGA.md](ORDER-INVENTORY-SAGA.md).

---

## 1. The problem it solves

Before this change, creating an order took two separate steps:

```text
1. INSERT INTO orders ...          (PostgreSQL)
2. kafkaTemplate.send(OrderCreatedEvent)   (Kafka)
```

These two systems can't share one transaction. That's the **dual-write problem**, and it can fail in two ways:

| What fails | Result |
|------------|--------|
| Kafka is down or slow when the order is saved | The order is saved but the event is lost. Inventory never hears about it, and the order stays `CREATED` forever. |
| The event is sent, then the database transaction rolls back | Inventory reserves stock for an order that doesn't exist. |

## 2. The fix

The event is written to an **`outbox_events` table in the same database transaction as the order**. A background **relay** then reads unpublished rows and sends them to Kafka.

```text
                   ┌─────────────── one DB transaction ───────────────┐
POST /orders ────► │ INSERT INTO orders ...                           │
                   │ INSERT INTO outbox_events (topic, key, payload)  │
                   └──────────────────────────────────────────────────┘
                                          │  commit: both rows or neither
                                          ▼
               OutboxRelay (every 1 s) ── SELECT … WHERE published_at IS NULL
                                          ORDER BY id FOR UPDATE SKIP LOCKED
                                          │
                                          ├─ send to Kafka, wait for acknowledgement
                                          └─ set published_at = now()
```

The order and its event are now saved together or not at all. Kafka being down only **delays** events; it can no longer lose them.

---

## 3. What was added

### New files (order-service)

| File | What it does |
|------|--------------|
| `entity/OutboxEvent.java` | The `outbox_events` table: `topic`, `message_key`, `payload` (the event as JSON text), `event_type`, `aggregate_type`/`aggregate_id` (which order it's for), `created_at`, `published_at` (null until sent), `attempts`, `last_error`. Indexed on `(published_at, id)` so finding pending rows stays fast. |
| `repository/OutboxEventRepository.java` | `lockNextUnpublished(limit)`: a native query that fetches the oldest pending rows with `FOR UPDATE SKIP LOCKED`. Also `deletePublishedBefore(cutoff)` for cleanup. |
| `event/publisher/OutboxOrderEventPublisher.java` | **Replaces** `OrderEventPublisherImpl`. It implements the same `OrderEventPublisher` interface, but it saves an outbox row instead of calling Kafka. It uses `@Transactional(propagation = MANDATORY)`, so calling it outside a transaction throws an error instead of quietly writing the row separately from the order. |
| `outbox/OutboxRelay.java` | Scheduled every `poll-interval-ms`. It publishes pending rows oldest first, waits for Kafka to acknowledge each one, then sets `published_at`. It stops at the first failure so events for an order are never sent out of order. The failed row keeps `attempts` and `last_error` and is retried on the next run. Full batches are drained straight away. A second job, every hour, deletes published rows older than `retention-days`. |
| `config/OutboxConfig.java` | Turns on scheduling. Defines `outboxKafkaTemplate`, a string producer (the payload is already JSON) with `acks=all`, idempotence on, and short timeouts so an outage fails in about 5 seconds instead of blocking for a minute. Replaces Spring's default producer listener, which would log every failed payload at ERROR. |
| `config/OutboxProperties.java` | Settings under `app.outbox.*` (below). |

### Changed files

| File | Change |
|------|--------|
| `event/publisher/OrderEventPublisherImpl.java` | **Deleted.** Replaced by `OutboxOrderEventPublisher` |
| `resources/application.yml` | Added `app.outbox.*`. Removed the `spring.kafka.producer` block, because order events are now sent by the relay's own template and dead letters by `KafkaConfig`'s own producer. |

**`OrderServiceImpl` did not change.** It still calls `orderEventPublisher.publishOrderCreatedEvent(...)` inside its existing `@Transactional` methods. Only the implementation behind the interface changed.

### Settings (`order-service/src/main/resources/application.yml`)

```yaml
app:
  outbox:
    poll-interval-ms: 1000   # pause between relay runs
    batch-size: 50           # most events sent per run
    send-timeout-ms: 10000   # wait for Kafka to acknowledge one event
    retention-days: 7        # published rows older than this are deleted
```

### New tests

| Test | What it proves |
|------|----------------|
| `outbox/OutboxOrderEventPublisherTest` (1 test) | The publisher writes an outbox row (topic, key = order ID, payload containing `orderId`, `productId`, `quantity`, `eventId`, `correlationId`) and doesn't call Kafka. |
| `outbox/OutboxRelayTest` (5 tests) | Events are sent in id order and marked published. The relay stops at the first failure, so later rows stay untouched. A failed event is retried on the next run and succeeds. Full batches keep draining. An empty outbox sends nothing. |
| `scripts/test-outbox-kafka-outage.ps1` | End to end against the real stack (see below). |

### Database
`outbox_events` is part of order-service's Flyway migration `V1__initial_schema.sql`, together with its `(published_at, id)` index. See [DATABASE-MIGRATIONS.md](DATABASE-MIGRATIONS.md).

---

## 4. Guarantees and trade-offs

| Property | How it's achieved |
|----------|-------------------|
| **No lost events** | The event row commits with the order. If Kafka is down, the row waits and is retried every second. |
| **No events for rolled-back orders** | If the order transaction rolls back, the outbox row rolls back with it. |
| **Delivered at least once** | If the service stops after Kafka accepted an event but before `published_at` was committed, the event is sent again. inventory-service already handles duplicates (the unique reservation per order), so this is safe. |
| **In order** | Rows are sent in `id` order, the relay stops at the first failure, and the Kafka key is the order ID, so all of an order's events land on the same partition. |
| **Safe with several instances** | `FOR UPDATE SKIP LOCKED` means two order-service instances never lock the same row. Each takes a different batch. |
| **Bounded table size** | Published rows are deleted after 7 days. |

**Trade-offs to know:**
- **Latency.** An event reaches Kafka after up to about `poll-interval-ms` (1 s). The tests measured 0.3 to 1.05 s.
- **Blocking row.** A row that can never be sent would hold up the rows behind it, because the relay keeps order. In practice the payload is already serialized and the topic is fixed, so the realistic failure is Kafka being unavailable, which clears when Kafka recovers. `attempts` and `last_error` show when a row is stuck.
- **Ordering across instances.** With several instances running at once, two batches can be published in parallel, so strict global ordering only holds with a single instance. Per-order ordering is what the saga needs, and the idempotent consumers tolerate the rest.
- **Next step.** For higher volume, the polling relay can be replaced with Change Data Capture (Debezium reading the PostgreSQL WAL) with no change to the service code.

---

## 5. How it was tested

### Unit tests: `mvn clean install`
**48 tests, all passing.** 6 are new outbox tests; the other 42 are the existing tests.

### End-to-end saga test, now publishing through the outbox
`scripts/test-order-saga.ps1`: **19 of 19 checks passed**. It was run three times on the outbox builds.

The `outbox_events` table afterwards showed every order event went through the outbox, each published on its first attempt:

```text
 id | aggregate_id |   event_type    |       topic        | attempts | published | delay_s
  1 | 24           | ORDER_CREATED   | order.created.v1   |        1 | t         |    1.05
  2 | 25           | ORDER_CREATED   | order.created.v1   |        1 | t         |    0.28
  4 | 24           | ORDER_CANCELLED | order.cancelled.v1 |        1 | t         |    0.70
  ...
```

### Kafka outage test
`scripts/test-outbox-kafka-outage.ps1`: **9 of 9 checks passed**. It was run three times on the outbox builds.

| Step | Checked |
|------|---------|
| Stop the Kafka container | Container is stopped |
| `POST /orders` while Kafka is down | Returns `CREATED` in under 3 s, without waiting on Kafka |
| Wait 12 s | The outbox row exists with `published_at` null, `attempts ≥ 1`, and `last_error` filled in; the order is still `CREATED` |
| Start Kafka | The order becomes `CONFIRMED`, the outbox row is published, and inventory reserved exactly 2 units (not more) |

Relay log during the outage:
```
Could not publish outbox event 17 (attempt 1), will retry: TimeoutException: Topic order.created.v1 not present in metadata after 5000 ms.
Could not publish outbox event 17 (attempt 2), will retry: TimeoutException: Topic order.created.v1 not present in metadata after 5000 ms.
Published outbox event 17 (ORDER_CREATED) for Order … to order.created.v1
```
One outage run, from the database: the row was created at 23:31:56, retried 3 times, and published at 23:32:32 on attempt 4, once Kafka was back.

### Problems found by running it
1. **An unclear error message.** The first outage run saved `last_error = "TimeoutException: null"`. The relay now records the underlying cause, for example `Topic order.created.v1 not present in metadata after 5000 ms`.
2. **Failed payloads in the logs.** Spring's default `LoggingProducerListener` logged an ERROR with the entire event payload on every failed attempt, which duplicated the relay's warning and wrote order data to the logs. The outbox template now uses a silent listener. The final outage run logged **0 ERROR lines** in order-service.

### Not tested end to end
The rollback case: an order transaction that fails *after* the outbox row is written. It is guaranteed because both rows are written by the same JPA transaction, and `MANDATORY` stops the publisher from being called outside one. But there is no API call that makes the order transaction fail after that point, so no automated test triggers it. A `@SpringBootTest` with Testcontainers PostgreSQL would be the way to cover it.

---

## 6. How to run it

```powershell
docker compose up -d
mvn clean install
java -jar order-service\target\order-service-0.0.1-SNAPSHOT.jar
java -jar inventory-service\target\inventory-service-0.0.1-SNAPSHOT.jar

powershell -ExecutionPolicy Bypass -File scripts\test-order-saga.ps1
powershell -ExecutionPolicy Bypass -File scripts\test-outbox-kafka-outage.ps1   # stops Kafka for about a minute
```

To look at the outbox yourself:
```powershell
docker exec ecommerce-postgres psql -U postgres -d orderdb -c "SELECT id, aggregate_id, event_type, attempts, published_at, last_error FROM outbox_events ORDER BY id DESC LIMIT 10;"
```

---

## 7. Explaining it in an interview

> "Saving an order and publishing its event is a dual write across two systems, so either one can fail on its own. I used the Transactional Outbox pattern: the event goes into an outbox table in the same database transaction as the order, so they commit or roll back together. A scheduled relay publishes pending rows in order using `FOR UPDATE SKIP LOCKED`, so several instances can run it safely. It marks rows published only after Kafka acknowledges them. That gives at-least-once delivery, which works because the consumers are idempotent. I tested it by stopping Kafka: the API kept accepting orders, the events waited in the outbox, and as soon as Kafka came back they were published and the orders were confirmed. To scale further, the next step would be CDC with Debezium instead of polling."
