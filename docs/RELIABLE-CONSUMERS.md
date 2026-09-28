# Reliable Consumers

This document describes how the Kafka consumers in **order-service** and **inventory-service** handle duplicate events and failures: what was added, how it works, and how it was tested. It builds on [ORDER-INVENTORY-SAGA.md](ORDER-INVENTORY-SAGA.md) and [TRANSACTIONAL-OUTBOX.md](TRANSACTIONAL-OUTBOX.md).

---

## 1. The problem

Kafka delivers every event **at least once**. The outbox relay can also resend an event if the service stops at the wrong moment. So every consumer must expect:

| Situation | What goes wrong without protection |
|-----------|------------------------------------|
| The **same event arrives twice** | Stock is reserved or released twice, or an order changes status twice |
| An event **fails for a moment**, e.g. the database is briefly unavailable | Without a retry, the event is lost |
| An event **fails every time**, e.g. it refers to an order that doesn't exist | Retrying forever blocks every event behind it on that partition |

---

## 2. What was added

### 2.1 Processed-event store (idempotent consumer)

Each service has a `processed_events` table. Each consumer records the ID of every event it handles:

```text
processed_events
 id | consumer                              | event_id                             | processed_at
 11 | inventory-service:order.created.v1    | ff55c3c7-a6c2-42ef-a5d6-1593207003e3 | 2026-09-29 00:11:21
 13 | inventory-service:order.cancelled.v1  | 888792ec-fcbc-43de-97bf-0a418d5fb109 | 2026-09-29 00:11:50
      UNIQUE (consumer, event_id)
```

How a consumer uses it:

```text
Kafka ──► Listener ──► Handler (@Transactional) ──────────────────────────────────┐
                        1. INSERT INTO processed_events … ON CONFLICT DO NOTHING    │ one
                           → 1 row inserted: first time, go on                     │ DB
                           → 0 rows inserted: duplicate, skip                      │ transaction
                        2. do the work (confirm order / reserve / release stock)   │
                       ────────────────────────────────────────────────────────────┘
```

Why it's built this way:

| Design choice | Reason |
|---------------|--------|
| **Record the event and do the work in one transaction** | If the work fails, the record rolls back too, so the retry runs normally. An event is never marked processed unless its effect was saved. |
| **Insert first with `ON CONFLICT DO NOTHING`**, not "check, then insert" | If two copies arrive at the same moment, the second insert waits on the unique index until the first commits, then inserts nothing. "Check, then insert" would let both through. |
| **Key is `(consumer, event_id)`** | Each listener tracks its own events, so one event can be handled by several consumers. |
| **`@Transactional(propagation = MANDATORY)`** on `markProcessed` | It throws an error if called outside a transaction, so the record can't be saved separately from the work. |
| **Records are deleted after 7 days** (hourly job) | Keeps the table small. Kafka redelivery happens within seconds or minutes, not days. |

**Each consumer and what it does with a duplicate:**

| Service | Consumer | On a duplicate |
|---------|----------|----------------|
| order-service | `order-service:inventory.reserved.v1` | Skipped |
| order-service | `order-service:inventory.reservation-failed.v1` | Skipped |
| inventory-service | `inventory-service:order.cancelled.v1` | Skipped |
| inventory-service | `inventory-service:order.created.v1` | Stock is **not** touched, but the stored reply is **sent again** (see below) |

**Why a duplicate `OrderCreatedEvent` still sends a reply:** inventory commits the reservation first and then sends its reply. If the reply fails to send, the listener fails and Kafka redelivers the **same** event, with the same `eventId`. Skipping it completely would leave the order `CREATED` forever. So on a duplicate the handler calls `storedOutcome(orderId)`, a **read-only** lookup of the result saved the first time, and the reply is sent again. Order Service then ignores the extra reply, because the order is no longer `CREATED`.

A duplicate **never** calls `reserveStock`. If nothing is stored for that order, the result is `IGNORED` and no reply is sent. An earlier version replayed through `reserveStock`, which would reserve stock if an event ID was ever reused for a different order. Testing found this; see section 4.

### 2.2 Protection in layers

The event-ID check is the first of three layers. Each one catches a case the others can't:

| Layer | Catches | Example from the tests |
|-------|---------|------------------------|
| 1. **Processed event ID** (new) | An exact redelivery of the same event | `Skipping duplicate OrderCancelledEvent 888792ec…` |
| 2. **One reservation per order** (unique `order_id` in `stock_reservations`) | The same order arriving as a *different* event, e.g. a manual resend | `Order 49 already handled (reservation RESERVED)` |
| 3. **Order-status guard** (only `CREATED` orders change) | A re-sent inventory reply, which gets a *new* eventId | `Ignoring inventory reservation for order 49 in status CONFIRMED` |

### 2.3 Retries with exponential backoff, then a dead-letter topic

Both services use `DefaultErrorHandler` with `DeadLetterPublishingRecoverer`. This change switched them from a fixed 1-second retry to **exponential backoff**:

```text
attempt 1 ── fails ── wait 1 s ── attempt 2 ── fails ── wait 2 s ── attempt 3 ── fails ── wait 4 s ── attempt 4 ── fails
                                                                                                       │
                                                                          publish original bytes to <topic>.DLT
                                                                          and move on to the next event
```

- **Retried** events: anything the listener throws, such as a database error or an order that can't be found.
- **Not retried** (sent straight to the DLT): messages that can't be read, such as invalid JSON. Spring Kafka classifies these conversion errors as fatal by default.
- **DLT headers** added by the recoverer: `kafka_dlt-original-topic`, `-partition`, `-offset`, `-timestamp`, `-consumer-group`, `-exception-fqcn`, `-exception-cause-fqcn`, `-exception-message` and `-exception-stacktrace`. Anyone looking at the DLT can see where the event came from and why it failed.
- **No partial records from failed attempts:** a failing attempt rolls back its `processed_events` row, so every retry runs in full.

---

## 3. Files

### order-service
| File | Status | What it does |
|------|--------|--------------|
| `entity/ProcessedEvent.java` | New | The `processed_events` table, with `UNIQUE (consumer, event_id)` |
| `repository/ProcessedEventRepository.java` | New | `insertIfAbsent` (native `INSERT … ON CONFLICT DO NOTHING`) and `deleteProcessedBefore` |
| `idempotency/ProcessedEventStore.java` | New | `markProcessed(consumer, eventId)` (`MANDATORY` transaction), plus the hourly cleanup |
| `event/consumer/InventoryEventHandler.java` | New | Transactional handler: records the event ID, then confirms or rejects the order |
| `event/consumer/InventoryEventListener.java` | Changed | Now only sets the correlation ID and hands off to the handler |
| `config/KafkaConfig.java` | Changed | `FixedBackOff(1 s, 3)` replaced by `ExponentialBackOffWithMaxRetries(3)`: 1 s, 2 s, 4 s |
| `resources/application.yml` | Changed | `app.idempotency.retention-days: 7` |

### inventory-service
| File | Status | What it does |
|------|--------|--------------|
| `entity/ProcessedEvent.java`, `repository/ProcessedEventRepository.java`, `idempotency/ProcessedEventStore.java` | New | Same as order-service |
| `event/consumer/OrderEventHandler.java` | New | Transactional handler for `order.created` (a duplicate returns the stored outcome, read-only) and `order.cancelled` (a duplicate is skipped) |
| `service/StockReservationService.java`, `service/impl/StockReservationServiceImpl.java` | Changed | Added `storedOutcome(orderId)`: returns the saved result without reserving anything |
| `event/consumer/OrderEventListener.java` | Changed | Hands off to the handler; still sends the reply only after the handler's transaction commits |
| `config/SchedulingConfig.java` | New | Turns on scheduling for the cleanup job |
| `config/KafkaConfig.java` | Changed | Exponential backoff, same as order-service |
| `resources/application.yml` | Changed | `app.idempotency.retention-days: 7` |

### Tests and scripts
| File | Status |
|------|--------|
| `order-service/.../event/consumer/InventoryEventHandlerTest.java` | New, 5 unit tests |
| `order-service/.../idempotency/ProcessedEventStoreIntegrationTest.java` | New, 4 integration tests (real PostgreSQL) |
| `inventory-service/.../event/consumer/OrderEventHandlerTest.java` | New, 5 unit tests |
| `inventory-service/.../service/StockReservationServiceTest.java` | 2 tests added for `storedOutcome` |
| `inventory-service/.../integration/ReliableConsumerIntegrationTest.java` | New, 5 integration tests (real PostgreSQL + Kafka) |
| `scripts/test-reliable-consumers.ps1` | New end-to-end test |
| `scripts/test-order-saga.ps1` | Scenario 7 now uses unique event IDs, like real events |

### Build
| File | Change |
|------|--------|
| `pom.xml` (parent) | Testcontainers 1.21.3, which supports the `apache/kafka` image. Surefire sets `api.version=1.44`, because Docker Engine 29+ rejects the older API version Testcontainers' Docker client asks for by default. |
| `order-service/pom.xml`, `inventory-service/pom.xml` | Test-scope dependencies: `spring-boot-testcontainers`, `testcontainers` `junit-jupiter`, `postgresql`, and `kafka` (inventory only) |

### Database
Both `processed_events` tables are created by Hibernate on first start. No migration is needed. After the first start, both databases were checked for the unique constraint that `ON CONFLICT` depends on:
```text
orderdb                | uk_processed_events_consumer_event | UNIQUE (consumer, event_id)
ecommerce_inventory_db | uk_processed_events_consumer_event | UNIQUE (consumer, event_id)
```
On first start Hibernate logs `constraint "uk_processed_events_consumer_event" … does not exist, skipping`. That comes from it trying to drop the constraint before creating it, and is harmless.

---

## 4. How it was tested

### Unit and integration tests: `mvn clean install`
**69 tests, all passing, 0 skipped:** 22 in order-service, 16 in product-service and 31 in inventory-service.

Unit tests (mocks):
- `InventoryEventHandlerTest` (order-service): a first event confirms or rejects the order, a duplicate is skipped, and each topic is tracked under its own consumer name.
- `OrderEventHandlerTest` (inventory-service): a first `OrderCreated` reserves stock; a duplicate returns the stored outcome and **never** calls `reserveStock`, even when nothing is stored (regression test); a first `OrderCancelled` releases stock; a duplicate is skipped.

### Integration tests with real PostgreSQL and Kafka (Testcontainers)
These run as part of `mvn clean install`. They start throwaway `postgres:16` and `apache/kafka:3.8.0` containers, the same images as `docker-compose.yml`, and remove them afterwards. If Docker isn't running they are **skipped, not failed**, so the build still works without Docker.

**`ReliableConsumerIntegrationTest`** (inventory-service, the real Spring app with real PostgreSQL and Kafka):

| # | Test | What it proves | Evidence from the run |
|---|------|----------------|-----------------------|
| 1 | 8 threads send the **same event** to the handler at the same instant | Reserved once; eventId recorded once; every copy returns `RESERVED` | All 8 ran in the same millisecond; 7 detected as duplicates |
| 2 | 8 threads send the **same order as 8 different events** at once | Stock reserved once. Losers hit the unique `order_id`, roll back fully, leave no `processed_events` row, and their retry replays the stored reservation. | 7 unique-constraint collisions, all rolled back |
| 2b | An eventId is **reused for a different order** | Returns `IGNORED`; stock untouched; no reservation for the new order (regression test for the bug below) | |
| 3 | **20 orders race for the last 5 units** | Exactly 5 reserved, 15 rejected, product `OUT_OF_STOCK`: the `SELECT … FOR UPDATE` lock prevents overselling | 5 reserved, 15 "Insufficient stock" |
| 4 | **Reply lost after commit.** The first send of `InventoryReservedEvent` fails; real Kafka redelivers the real event | The reply arrives on `inventory.reserved.v1` **exactly once**; the publisher is called twice; stock reserved once; eventId recorded once | Reserved at :25.987 → failure → redelivered at :27.138 (1 s backoff) → "Duplicate OrderCreatedEvent … re-sending the stored outcome" |

**`ProcessedEventStoreIntegrationTest`** (order-service, real PostgreSQL):
- 16 threads mark the same event at once, and **exactly one** is treated as new.
- A record written in a rolled-back transaction doesn't count, so the retry is processed normally.
- The same eventId is tracked separately for each consumer.
- `markProcessed` throws `IllegalTransactionStateException` outside a transaction (`MANDATORY`).

### End-to-end test against the real stack
`scripts/test-reliable-consumers.ps1`: **17 of 17 checks passed**, run twice, including once right after Kafka was restarted. The duplicates are **exact copies of real events**, read back from the outbox table and from the Kafka topic, not hand-written JSON.

| # | Scenario | Checked |
|---|----------|---------|
| 1 | Resend the real `OrderCreatedEvent` | Inventory recorded the eventId **once**; stock still 3 reserved, 7 available; the stored reply was sent again (2 replies on `inventory.reserved.v1`); order still `CONFIRMED` |
| 2 | Resend the real `InventoryReservedEvent` | Order-service recorded the eventId **once**; order still `CONFIRMED` |
| 3 | Cancel the order, then resend the real `OrderCancelledEvent` | Stock released once (0 reserved, 10 available); eventId recorded **once**; stock unchanged by the duplicate |
| 4 | Send an `InventoryReservedEvent` for an order that doesn't exist | It reaches `inventory.reserved.v1.DLT` only after the retries (at least 7 s). The headers show original topic `inventory.reserved.v1`, consumer group `order-group`, cause `OrderNotFoundException` and "Order not found with ID: …". **No** `processed_events` row remains from the failed attempts. |
| 5 | Place a new order afterwards | `CONFIRMED`: the consumers weren't blocked |

**Retry timing measured from the order-service log** (scenario 4):
```text
00:12:00.614  attempt 1
00:12:01.643  attempt 2   (+1.03 s)
00:12:03.666  attempt 3   (+2.02 s)
00:12:07.705  attempt 4   (+4.04 s)   → then published to inventory.reserved.v1.DLT
```

**Duplicate detection seen in the logs:**
```text
inventory-service  Duplicate OrderCreatedEvent ff55c3c7-… for order 49: stock untouched, re-sending the stored outcome
inventory-service  Skipping duplicate OrderCancelledEvent 888792ec-… for order 49
order-service      Skipping duplicate InventoryReservedEvent 2e99aabe-… for order 49
order-service      Ignoring inventory reservation for order 49 in status CONFIRMED     ← the re-sent reply (new eventId)
```
Across all runs, both services logged **0 unexpected ERROR lines**. The only errors were the deliberate "Order not found" failures in scenario 4, plus Spring Kafka's standard "Error handler threw an exception" line for each retry.

### End-to-end scripts on the final build
| Script | Result |
|--------|--------|
| `test-order-saga.ps1` | 19 of 19 checks passed, **two runs in a row** (the bug below only appeared on a second run) |
| `test-reliable-consumers.ps1` | 17 of 17 checks passed |
| `test-outbox-kafka-outage.ps1` | 9 of 9 checks passed |

Both services logged 0 unexpected ERROR lines.

### Problems found while testing

**A real code bug: a duplicate event could reserve stock.**
A regression run of the saga script failed scenario 7 (a cancellation that overtakes its creation event) with `reserved=2` where 0 was expected. The script used fixed event IDs (`"early-cancel"`, `"late-create"`), so on its second run both were already in `processed_events`:
1. The early cancel was correctly skipped as a duplicate, so no "cancelled" marker was stored for the new order.
2. The late create was also a duplicate, but the handler replayed it by calling `reserveStock`. With no reservation stored for that new order, `reserveStock` **reserved stock**.

Real event IDs are unique UUIDs, so normal traffic wouldn't trigger this. But it broke the promise that "a duplicate never touches stock". **Fix:** a duplicate now only calls the read-only `storedOutcome(orderId)`. Regression tests were added at both unit and integration level, the script now uses unique IDs like real events, and the saga test passed twice in a row afterwards.

**Test-script and environment problems** (the service logs and the DLT confirmed the services behaved correctly each time):
1. **Header lines split apart.** Some DLT headers hold binary numbers, such as the partition and offset, which can contain line-break bytes. So a record's headers and value don't always sit on one line, and the script now searches the DLT output as one block of text.
2. **Read timeout too short.** A 3-second read gave up before the console consumer had joined its group, so the script saw an empty topic. It now uses 8 seconds.
3. **PowerShell error on empty results.** `-like` on an empty result returns an array, which broke the `Check` function. This was fixed along with item 1.
4. **Testcontainers couldn't reach Docker.** The first integration run was silently *skipped*, not passed: Docker Engine 29 answered `400` to the old API version. Fixed with `api.version=1.44` in Surefire.
5. **Timezone rejected by PostgreSQL.** The JVM reported the legacy zone `Asia/Calcutta`, which PostgreSQL 16 rejects. The services already fix this in `main()` with `TimeZoneNormalizer`; the integration tests now call it too.

### Previously untested cases, now covered
| Case | Now tested by |
|------|---------------|
| Two copies of the same event at the exact same moment | `ReliableConsumerIntegrationTest` #1 and #2 (real PostgreSQL, 8 threads), `ProcessedEventStoreIntegrationTest` (16 threads) |
| Resending a reply after its first send failed | `ReliableConsumerIntegrationTest` #4 (real Kafka redelivery; the reply arrives exactly once) |

---

## 5. How to run it

```powershell
docker compose up -d
mvn clean install          # includes the Testcontainers integration tests (Docker must be running, or they're skipped)
java -jar order-service\target\order-service-0.0.1-SNAPSHOT.jar
java -jar inventory-service\target\inventory-service-0.0.1-SNAPSHOT.jar

powershell -ExecutionPolicy Bypass -File scripts\test-reliable-consumers.ps1
```

Look at what each consumer has processed:
```powershell
docker exec ecommerce-postgres psql -U postgres -d ecommerce_inventory_db -c "SELECT consumer, event_id, processed_at FROM processed_events ORDER BY id DESC LIMIT 10;"
docker exec ecommerce-postgres psql -U postgres -d orderdb -c "SELECT consumer, event_id, processed_at FROM processed_events ORDER BY id DESC LIMIT 10;"
```

Look at a dead-letter topic, with headers:
```powershell
docker exec ecommerce-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic inventory.reserved.v1.DLT --from-beginning --timeout-ms 8000 --property print.headers=true
```
You can also browse the `.DLT` topics in Kafka UI at http://localhost:8081.

---

## 6. Explaining it in an interview

> "Kafka and my outbox both deliver at least once, so every consumer is idempotent. Each listener inserts the event ID into a `processed_events` table with `ON CONFLICT DO NOTHING`, in the same transaction as its business change. A duplicate inserts nothing and is skipped. If the work fails, the record rolls back and the retry runs normally. Inserting first rather than checking first also handles two copies arriving at once, because the unique index makes the second one wait. It's one of three layers, along with one reservation per order and an order-status guard, because a re-sent reply gets a new event ID. One subtle case: when inventory sees a duplicate `OrderCreated`, it still re-sends its stored reply, because the duplicate may be a retry after the reply was lost. Failures retry with exponential backoff, 1, 2 and 4 seconds, and then go to a dead-letter topic with headers saying where the event came from and why it failed, so one bad event never blocks the partition. I tested all of it against real Kafka by replaying exact copies of real events."
