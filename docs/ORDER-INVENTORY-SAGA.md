# Order → Inventory Saga

This document describes the event-driven order fulfillment flow between **order-service** and **inventory-service**: what it does, how it is built, how it handles failures, and how it was tested.

---

## 1. What it does

When a customer places an order, the order is not confirmed until inventory has actually set stock aside for it. The two services never call each other directly. They coordinate only through Kafka events, as a **choreography-based saga**.

```text
 Client                Order Service                 Kafka                   Inventory Service
   │  POST /orders           │                          │                            │
   │────────────────────────►│ save order (CREATED)     │                            │
   │◄──── 201 CREATED ───────│── OrderCreatedEvent ────►│── order.created.v1 ───────►│
   │                         │                          │                            │ lock stock row
   │                         │                          │                            │ enough stock?
   │                         │                          │◄── inventory.reserved.v1 ──│  yes: reserve
   │                         │◄─ InventoryReservedEvent─│                            │
   │                         │ order → CONFIRMED        │                            │
   │                         │                          │◄─ inventory.reservation- ──│  no: reject
   │                         │◄─ ReservationFailedEvent─│    failed.v1               │
   │                         │ order → REJECTED + reason│                            │
   │                         │                          │                            │
   │  DELETE /orders/{id}    │                          │                            │
   │────────────────────────►│ order → CANCELLED        │                            │
   │                         │── OrderCancelledEvent ──►│── order.cancelled.v1 ─────►│ release stock
```

### Order statuses

| Status | Meaning |
|--------|---------|
| `CREATED` | Order saved; waiting for inventory to answer |
| `CONFIRMED` | Inventory reserved the stock |
| `REJECTED` | Inventory could not reserve the stock; `statusReason` says why |
| `CANCELLED` | Cancelled by the client; any reserved stock is returned |

An order is rejected when the product has no inventory record, the product is discontinued, or there isn't enough available stock. For example:

```json
{ "id": 13, "status": "REJECTED",
  "statusReason": "Insufficient stock for product 5d25fc4f-…: requested 20, available 7" }
```

### Kafka topics

| Topic | Producer | Consumer |
|-------|----------|----------|
| `order.created.v1` | order-service | inventory-service |
| `order.cancelled.v1` | order-service | inventory-service |
| `inventory.reserved.v1` | inventory-service | order-service |
| `inventory.reservation-failed.v1` | inventory-service | order-service |
| `<topic>.DLT` | the consuming service's error handler | none (for inspection) |

---

## 2. Reliability: what can go wrong and how it is handled

| Problem | How it's handled |
|---------|------------------|
| **The same event is delivered twice.** Kafka guarantees at-least-once delivery, so duplicates are normal. | Inventory keeps one `stock_reservations` row per order, with a **unique constraint on `order_id`**. A repeat event finds the existing row, sends the same answer again, and doesn't touch stock. Order Service only changes an order that is still `CREATED`, so a repeat answer does nothing. |
| **Two orders for the last units arrive at the same moment.** | The stock row is read with `SELECT … FOR UPDATE` (a pessimistic write lock), so reservations for the same product run one at a time and can't oversell. |
| **A cancellation arrives before its creation event.** They are on different topics, so Kafka doesn't guarantee their order. | If there's no reservation yet, the cancellation stores a `CANCELLED` marker. When the late creation event arrives, it sees the marker and reserves nothing. |
| **Two events for the same order are processed at the same moment.** | The unique constraint rejects the second insert, and the transaction rolls back. The error handler retries it, and the retry finds the existing row. (This happened for real during testing; see section 4.) |
| **The database or Kafka is briefly unavailable.** | A `DefaultErrorHandler` retries the event 3 times with exponential backoff (1 s, 2 s, 4 s). See [RELIABLE-CONSUMERS.md](RELIABLE-CONSUMERS.md). |
| **An event keeps failing, or can't be read at all** (for example, it isn't valid JSON). | After the retries (or straight away for unreadable messages), the original bytes are published to `<topic>.DLT`, and the consumer moves on to the next event instead of getting stuck. |
| **The reply event fails to send.** | Inventory sends its reply synchronously, after its database transaction commits. If the send fails, the listener fails, the incoming event is retried, and the idempotent reservation sends the same answer again. |
| **The two services use different Java classes for the same event.** | Producers don't add Java type headers to messages. Each consumer turns the JSON into its own small record class (for example `OrderCreatedMessage`), so neither service depends on the other's code. |
| **Tracing an order across the services.** | Each listener copies the event's `correlationId` into `CorrelationIdContext`, so the reply event carries the same correlation ID as the original request. |
| **Kafka is down when an order is created.** | ✅ Solved by the **Transactional Outbox**: order events are written to an `outbox_events` table in the same transaction as the order, and a relay publishes them once Kafka is reachable. See [TRANSACTIONAL-OUTBOX.md](TRANSACTIONAL-OUTBOX.md). |

---

## 3. What was changed

### common-module
| File | Change |
|------|--------|
| `event/EventType.java` | Added `INVENTORY_RESERVED` and `INVENTORY_RESERVATION_FAILED` |

### order-service
| File | Change |
|------|--------|
| `entity/Order.java` | Added `productId` (UUID) and `statusReason` |
| `entity/OrderStatus.java` | Added `CONFIRMED` and `REJECTED` |
| `dto/CreateOrderRequest.java` | `productId` is now **required** |
| `dto/OrderResponse.java` | Returns `productId` and `statusReason` |
| `mapper/OrderMapper.java` | Mapping rules for the new fields |
| `event/model/Order*Event.java` | Events now include `productId` |
| `event/mapper/OrderEventMapper.java` | Passes `productId` into events |
| `event/incoming/InventoryReservedMessage.java` | **New.** Order Service's view of the inventory reply |
| `event/incoming/InventoryReservationFailedMessage.java` | **New.** Same, for failures |
| `event/consumer/InventoryEventListener.java` | **New.** Listens to both inventory topics |
| `service/OrderService.java`, `service/impl/OrderServiceImpl.java` | Added `confirmOrder` and `rejectOrder`; both only change orders that are still `CREATED` |
| `config/KafkaConfig.java` | Replaced: JSON message converter, retry and dead-letter error handler, topic creation |
| `config/KafkaTopicProperties.java` | Added the inventory topic names |
| `resources/application.yml` | Consumer reads raw bytes, producer stops sending type headers, new topics |

### inventory-service
| File | Change |
|------|--------|
| `entity/StockReservation.java` | **New** table `stock_reservations`: one row per order (unique `order_id`) |
| `enums/ReservationStatus.java` | **New:** `RESERVED`, `REJECTED`, `RELEASED`, `CANCELLED` |
| `repository/StockReservationRepository.java` | **New** |
| `repository/InventoryRepository.java` | Added `findByProductIdForUpdate`, which uses a pessimistic write lock |
| `service/StockReservationService.java`, `service/impl/StockReservationServiceImpl.java` | **New.** Reserve and release logic |
| `service/ReservationResult.java` | **New.** The outcome the listener turns into a reply event |
| `event/incoming/OrderCreatedMessage.java`, `OrderCancelledMessage.java` | **New.** Inventory Service's view of the order events |
| `event/model/InventoryReservedEvent.java`, `InventoryReservationFailedEvent.java` | **New.** Outgoing events |
| `event/mapper/InventoryEventMapper.java` | **New** |
| `event/publisher/InventoryEventPublisher.java`, `…Impl.java` | **New.** Synchronous send |
| `event/consumer/OrderEventListener.java` | **New.** Listens to `order.created.v1` and `order.cancelled.v1` |
| `config/KafkaConfig.java`, `config/KafkaTopicProperties.java` | **New.** Same pattern as order-service |
| `resources/application.yml` | Removed three topic names that nothing used; added the saga topics |

### Other files
| File | Purpose |
|------|---------|
| `scripts/test-order-saga.ps1` | End-to-end test (see below) |
| `docker/postgres/migrations/001-order-status-saga.sql` | One-time fix for an `orderdb` created before this change (see below) |

### ⚠️ Breaking API change
`POST /api/v1/orders` now **requires `productId`**, the UUID of a product that has inventory:

```json
{ "productId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "productName": "MacBook Pro", "quantity": 2, "price": 2500.0 }
```

---

## 4. How it was tested

### Unit tests (`mvn clean install`): 42 tests, all passing
- **`StockReservationServiceTest`** (new, 10 tests): reserve when stock is available; out of stock when the last unit is reserved; reject for insufficient stock, no inventory and discontinued products; duplicate events replay without touching stock; a cancellation that arrives first blocks a later reservation; release returns stock; releasing twice does nothing.
- **`OrderServiceTest`** (4 new tests): `CREATED` → `CONFIRMED`; `CREATED` → `REJECTED` with a reason; answers for cancelled or already-confirmed orders are ignored.
- All existing product and inventory tests still pass.

### End-to-end test against the real stack: 19 of 19 checks passing
Setup: PostgreSQL 16 and Kafka 3.8 from `docker-compose.yml`, with all three services running as jars. The script was run three times in a row and passed each time.

| # | Scenario | Checked |
|---|----------|---------|
| 1 | Order within stock | Order `CONFIRMED`; 3 reserved, 7 available |
| 2 | Order above stock | Order `REJECTED` with "requested 20, available 7"; stock unchanged |
| 3 | Product with no inventory | Order `REJECTED`, "No inventory found" |
| 4 | Cancel a confirmed order | Order `CANCELLED`; stock back to 0 reserved, 10 available |
| 5 | Order the last 10 units | Order `CONFIRMED`; product `OUT_OF_STOCK`; the next order is `REJECTED` |
| 6 | Resend the same `OrderCreatedEvent` directly to Kafka | Still exactly 10 reserved; order still `CONFIRMED` |
| 7 | Send a cancellation, then the creation event for the same order | Stock untouched |
| 8 | Send invalid JSON to `order.created.v1` | Message is on `order.created.v1.DLT`; the next order still `CONFIRMED` |
| 9 | Create an order without `productId` | HTTP 400 |

The logs confirmed that scenarios 6 and 7 passed for the right reason, and not just because the timing happened to work out:
```
Order 15 already handled (reservation RESERVED), replaying its outcome
Order 996908228 cancelled before any reservation; recorded so it won't be reserved later
Order 996908228 already handled (reservation CANCELLED), replaying its outcome
```

### Problems found by running it (not visible from the code)
1. **Existing `orderdb` rejected the new statuses.** Hibernate 6 adds a CHECK constraint that lists the enum values. `ddl-auto: update` never updates an existing constraint, so a database created before this change rejects `CONFIRMED` and `REJECTED`. On the first test run, orders stayed `CREATED`, and the retry and dead-letter handling moved those replies to the `.DLT` topics as designed. Fixed with `docker/postgres/migrations/001-order-status-saga.sql`. A brand-new database was also checked: Hibernate creates it with all seven statuses, so it doesn't need the migration.
2. **A real race condition during startup.** Kafka still held old order events from before this change. A created event and a cancelled event for the same old order were processed at the same moment and both tried to insert its reservation row. The unique constraint blocked the second insert, the error handler retried it one second later, and the retry saw the existing row. Nothing was double-counted and nothing went to the DLT.
3. **Old events without `productId`** were rejected cleanly with "Order has no valid product ID or quantity" and didn't crash the consumer.

---

## 5. How to run it yourself

```powershell
# 1. Infrastructure
docker compose up -d

# 2. Only if your orderdb existed before this change (safe to run more than once)
Get-Content docker\postgres\migrations\001-order-status-saga.sql | docker exec -i ecommerce-postgres psql -U postgres -d orderdb

# 3. Build and run the unit tests
mvn clean install

# 4. Start the services (separate terminals)
java -jar order-service\target\order-service-0.0.1-SNAPSHOT.jar
java -jar inventory-service\target\inventory-service-0.0.1-SNAPSHOT.jar

# 5. End-to-end test
powershell -ExecutionPolicy Bypass -File scripts\test-order-saga.ps1
```

To try it by hand in Swagger:
1. Create stock at http://localhost:8083/swagger-ui.html with `POST /api/v1/inventory`, using any UUID as `productId`.
2. Place an order at http://localhost:8080/swagger-ui.html with `POST /api/v1/orders`, using the same `productId`.
3. Call `GET /api/v1/orders/{id}` a second later. The status will be `CONFIRMED` or `REJECTED`.
4. Watch the events in Kafka UI at http://localhost:8081.

---

## 6. Explaining it in an interview

> "Orders and inventory coordinate through a choreography saga over Kafka, with no direct calls between them. Kafka delivers at least once, so every consumer is idempotent. Inventory keys reservations by order ID with a unique constraint, and duplicates replay the original answer. Stock rows are locked with `SELECT … FOR UPDATE` so concurrent orders can't oversell. Because created and cancelled events travel on separate topics, a cancellation can arrive first, so I record a marker that blocks the late reservation. Failures retry with backoff and then go to a dead-letter topic, so one bad message can't block the partition. Order events are published through a transactional outbox, so an order and its event are always saved together, even when Kafka is down."
