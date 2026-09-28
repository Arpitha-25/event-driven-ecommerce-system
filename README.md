# 🛒 Event-Driven E-Commerce Platform

> An event-driven microservices backend built with **Java 21, Spring Boot 3, Apache Kafka, PostgreSQL and Docker**.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen)
![Apache Kafka](https://img.shields.io/badge/Apache-Kafka-black)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED)

---

# 📖 Overview

The platform is split into independent services, each with its own PostgreSQL database and REST API. Order and Inventory services coordinate order fulfillment through a **choreography-based saga over Apache Kafka**: an order is confirmed only after inventory reserves its stock, and cancelling an order releases that stock. Order events are published through a **Transactional Outbox**, so an order and its event are always saved together, even while Kafka is down. Product Service publishes catalog events. A shared `common-module` provides the API response format, base event model, exceptions and utilities used by every service.

📄 Design, failure handling and test results:
- [docs/ORDER-INVENTORY-SAGA.md](docs/ORDER-INVENTORY-SAGA.md): the order → inventory saga
- [docs/TRANSACTIONAL-OUTBOX.md](docs/TRANSACTIONAL-OUTBOX.md): reliable event publishing from order-service
- [docs/RELIABLE-CONSUMERS.md](docs/RELIABLE-CONSUMERS.md): idempotent consumers, retries and dead-letter topics
- [docs/DOCKER-AND-SYSTEM-TESTS.md](docs/DOCKER-AND-SYSTEM-TESTS.md): running everything in Docker, and the full-flow system test

---

# 🏗️ Architecture

```text
                        common-module
                              │
         ┌────────────────────┼────────────────────┐
         ▼                    ▼                    ▼
   Order Service        Product Service     Inventory Service
      (:8080)               (:8082)              (:8083)
         │                    │                    │
         ▼                    ▼                    ▼
      orderdb        ecommerce_product_db  ecommerce_inventory_db
         │                    │                    │
         └────────────────────┼────────────────────┘
                              ▼
                        Apache Kafka  ──►  Kafka UI (:8081)
```

## Order saga

```text
POST /orders ──► Order (CREATED) + outbox row          (one DB transaction)
                          │
                    OutboxRelay ──► order.created.v1 ──► Inventory: lock stock row
                                                              │
                     ┌──── inventory.reserved.v1 ◄────────────┤ enough stock → reserve
                     ▼                                        │
              Order CONFIRMED                                 │
                     ┌── inventory.reservation-failed.v1 ◄────┘ otherwise
                     ▼
              Order REJECTED (+ reason)

DELETE /orders/{id} ──► Order CANCELLED + outbox row
                                  │
                            OutboxRelay ──► order.cancelled.v1 ──► Inventory releases stock
```

---

# 🧩 Services

| Service | Responsibility | Kafka |
|---------|----------------|-------|
| Common Module | Shared DTOs, base event, event metadata, exceptions, correlation ID filter, utilities | — |
| Order Service | Create, read, update and cancel orders; confirms or rejects orders based on inventory's reply | Publishes order events; consumes inventory events |
| Product Service | Product catalog management | Publishes product events |
| Inventory Service | Stock levels per product; reserves stock for new orders and releases it for cancelled ones | Consumes order events; publishes inventory events |

---

# 🔌 REST API

| Service | Base path | Endpoints |
|---------|-----------|-----------|
| Order | `/api/v1/orders` | `POST /`, `GET /{id}`, `PUT /{id}`, `DELETE /{id}` |
| Product | `/api/v1/products` | `POST /`, `GET /`, `GET /{id}`, `PUT /{id}`, `DELETE /{id}` |
| Inventory | `/api/v1/inventory` | `POST /`, `GET /`, `GET /{id}`, `GET /product/{productId}`, `PUT /{id}`, `DELETE /{id}` |

Each service serves Swagger UI at `/swagger-ui.html` and Actuator at `/actuator/health`, `/actuator/info` and `/actuator/metrics`.

---

# 🔄 Kafka Events

| Service | Event | Topic |
|---------|-------|-------|
| Order | `OrderCreatedEvent` | `order.created.v1` |
| Order | `OrderUpdatedEvent` | `order.updated.v1` |
| Order | `OrderCancelledEvent` | `order.cancelled.v1` |
| Product | `ProductCreatedEvent` | `product.created.v1` |
| Product | `ProductUpdatedEvent` | `product.updated.v1` |
| Product | `ProductDeletedEvent` | `product.deleted.v1` |
| Inventory | `InventoryReservedEvent` | `inventory.reserved.v1` |
| Inventory | `InventoryReservationFailedEvent` | `inventory.reservation-failed.v1` |

Order events are written to the `outbox_events` table in the same transaction as the order, then published by `OutboxRelay`, usually within about a second.
Every consumer records the event IDs it has handled (`processed_events`), so a redelivered event is skipped. Events that still fail after 3 retries (1 s, 2 s, 4 s backoff), or that can't be read at all, are moved to `<topic>.DLT`.


---

# ⭐ Features

- Multi-module Maven build
- Layered design: controller → service interface/impl → repository
- DTOs with Bean Validation, mapped using MapStruct
- Global exception handling with a standard `ApiResponse` / `ApiError` format
- Correlation ID filter shared through the common module
- Event metadata factory and `EventType` enum
- Event publisher abstraction with versioned Kafka topics
- Choreography-based saga between Order and Inventory services
- Transactional Outbox: order events saved atomically with the order and published by a scheduled relay (`FOR UPDATE SKIP LOCKED`, ordered, retried, at-least-once)
- Idempotent consumers: every listener records processed event IDs in the same transaction as its work (`INSERT … ON CONFLICT DO NOTHING`), backed by reservations keyed by order ID and order-status guards
- Pessimistic row locking (`SELECT … FOR UPDATE`) so concurrent orders can't oversell stock
- Handles a cancellation that arrives before its creation event
- Retries with exponential backoff (1 s, 2 s, 4 s) and dead-letter topics carrying the failure reason in headers (`DefaultErrorHandler` + `DeadLetterPublishingRecoverer`)
- Consumers own their event classes; no shared Java types or type headers between services
- Correlation ID carried from the HTTP request through every saga event
- OpenAPI / Swagger documentation
- Spring Boot Actuator health, info and metrics
- Unit tests with JUnit 5 and Mockito
- Integration tests on real PostgreSQL and Kafka (Testcontainers): concurrent duplicate events, racing orders for the last units, and a lost reply redelivered by Kafka
- End-to-end test scripts run against the real Kafka and PostgreSQL stack, including a Kafka outage test
- Full-flow system test: the real service images, PostgreSQL and Kafka in Docker, driven only through the REST APIs
- Every service in Docker: multi-stage images (Maven build, then a non-root JRE runtime), one `docker compose up` for the whole system

---

# 🛠️ Technology Stack

| Area | Tools |
|------|-------|
| Language & framework | Java 21, Spring Boot 3.3 (Web, Data JPA, Validation, Actuator) |
| Messaging | Apache Kafka 3.8, Spring Kafka, Kafka UI |
| Database | PostgreSQL 16 |
| Mapping & boilerplate | MapStruct, Lombok |
| API docs | springdoc-openapi (Swagger UI) |
| Testing | JUnit 5, Mockito, Spring Boot Test, Testcontainers (PostgreSQL, Kafka) |
| Build & run | Maven, Docker (multi-stage images), Docker Compose |

---

# 📂 Project Structure

```text
event-driven-ecommerce-system
├── common-module         # constant, dto, event, exception, filter, util
├── order-service         # each service has its own Dockerfile
├── product-service
├── inventory-service
├── system-tests          # full-flow test in Docker (Maven profile: -Psystem-tests)
├── docker
│   └── postgres
│       ├── init.sql      # creates the three service databases
│       └── migrations    # one-off fixes for databases created by older versions
├── docs                  # design, test results and how-tos for each feature
├── scripts               # end-to-end tests: saga, reliable consumers, Kafka outage
├── docker-compose.yml    # the whole system: PostgreSQL, Kafka, Kafka UI and the three services
└── pom.xml               # parent POM
```

Each service follows the same layout: `controller`, `dto`, `entity`, `mapper`, `repository`, `service`, `exception`, plus `config` and `event` in the services that use Kafka. order-service also has `outbox` (the relay).

---

# 💻 Running Locally

## Option A: everything in Docker (only Docker needed)

```bash
git clone https://github.com/Arpitha-25/event-driven-ecommerce-system.git
cd event-driven-ecommerce-system

docker compose up -d --build     # first build takes a few minutes; later builds about 1 minute
docker compose ps                # all containers Up; postgres and kafka (healthy)
docker compose down              # stop (add -v to also delete the database volume)
```

## Option B: infrastructure in Docker, services from your IDE or `java -jar`

**Prerequisites:** Java 21, Maven and Docker.

```bash
docker compose up -d postgres kafka kafka-ui   # infrastructure only (ports 8080/8082/8083 stay free)
mvn clean install                              # unit and integration tests (Testcontainers needs Docker)

java -jar order-service/target/order-service-0.0.1-SNAPSHOT.jar          # :8080
java -jar product-service/target/product-service-0.0.1-SNAPSHOT.jar      # :8082
java -jar inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar  # :8083
```

If the service containers from Option A are running, stop them first (`docker compose stop order-service product-service inventory-service`), because they use the same ports.

| URL | Purpose |
|-----|---------|
| http://localhost:8080/swagger-ui.html | Order Service API |
| http://localhost:8082/swagger-ui.html | Product Service API |
| http://localhost:8083/swagger-ui.html | Inventory Service API |
| http://localhost:8081 | Kafka UI |

## Tests

```bash
mvn clean install                               # unit + integration tests (real PostgreSQL/Kafka via Testcontainers)
mvn install -Psystem-tests                      # also the full-flow system test: builds the images and runs them in Docker
```

End-to-end scripts, run against a running system (Option A or B):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\test-order-saga.ps1
powershell -ExecutionPolicy Bypass -File scripts\test-reliable-consumers.ps1
powershell -ExecutionPolicy Bypass -File scripts\test-outbox-kafka-outage.ps1   # stops Kafka for about a minute
```

> If your `orderdb` was created before the saga was added, run
> `docker/postgres/migrations/001-order-status-saga.sql` once. See the saga doc for details.

Services connect to `localhost` with `postgres` / `postgres` credentials by default. Override them with
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
and `SPRING_KAFKA_BOOTSTRAP_SERVERS`.

---

# 👨‍💻 Author

**Arpitha R**

- GitHub: https://github.com/Arpitha-25
