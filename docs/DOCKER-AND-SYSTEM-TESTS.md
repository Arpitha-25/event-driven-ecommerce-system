# Docker and the Full-Flow System Test

This document covers two additions:
1. **Every service runs in Docker.** Each service has its own Dockerfile, and one `docker compose up` starts the whole system.
2. **A full-flow system test.** The real service images run with PostgreSQL and Kafka in Docker, and the order → inventory → order-status flow is tested only through the REST APIs.

It also describes a Kafka bug that the system test found, and its fix.

---

## 1. Running the whole system

```bash
docker compose up -d --build
```

| Container | Image | Port | Waits for |
|-----------|-------|------|-----------|
| `ecommerce-postgres` | `postgres:16` | 5432 | none |
| `ecommerce-kafka` | `apache/kafka:3.8.0` | 9092 | none |
| `ecommerce-kafka-ui` | `provectuslabs/kafka-ui` | 8081 | Kafka |
| `ecommerce-order-service` | `ecommerce/order-service` | 8080 | PostgreSQL **healthy**, Kafka **healthy** |
| `ecommerce-product-service` | `ecommerce/product-service` | 8082 | PostgreSQL **healthy**, Kafka **healthy** |
| `ecommerce-inventory-service` | `ecommerce/inventory-service` | 8083 | PostgreSQL **healthy**, Kafka **healthy** |

- **Kafka health check.** Kafka now has one (`kafka-broker-api-versions.sh`), so the services start only once the broker actually answers, not just when its container is up.
- **Connection settings.** Inside the Docker network, services reach PostgreSQL at `postgres:5432` and Kafka at `kafka:29092`, the internal listener. Both are set with the environment variables the services already supported (`SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`), so no code or config files changed for Docker.
- **Automatic restart.** `restart: on-failure` restarts a service if it crashes.
- **Infrastructure only.** `docker compose up -d postgres kafka kafka-ui` starts just the infrastructure, for running services from the IDE.

### The Dockerfiles

`order-service/Dockerfile`, `product-service/Dockerfile` and `inventory-service/Dockerfile` are identical apart from the module name and port.

```text
Stage 1: build (maven:3.9-eclipse-temurin-21)
  copy the parent POM, all module POMs, common-module/src and this service's src
  mvn -pl <service> -am package -Dmaven.test.skip=true
      └─ the Maven repository is a BuildKit cache mount shared by all images and rebuilds

Stage 2: runtime (eclipse-temurin:21-jre)
  non-root user "app"
  copy only the Spring Boot jar
  java -XX:MaxRAMPercentage=75 -jar app.jar
```

| Decision | Why |
|----------|-----|
| **Build inside Docker (multi-stage)** | A fresh clone needs only Docker. You don't need Java or Maven installed, and the image doesn't depend on whatever happens to be in your local `target/`. |
| **Build context is the repository root** | Each service needs the parent POM and `common-module`. `.dockerignore` keeps the context small by leaving out `target/`, IDE folders, docs and scripts. |
| **Shared Maven cache (`RUN --mount=type=cache,…,sharing=locked`)** | Dependencies download once for all three images and every rebuild. `locked` makes parallel builds take turns, because a Maven repository isn't safe for concurrent writes. |
| **`-Dmaven.test.skip=true`** | Tests run in `mvn install`, not inside the image build. This also means test-only libraries such as Testcontainers aren't downloaded. |
| **JRE runtime image, non-root user** | Smaller image with less inside it to attack. `docker exec … whoami` returns `app`. |
| **`-XX:MaxRAMPercentage=75`** | The JVM sizes its heap from the container's memory limit, not the host's. |

**Build times measured on this machine:**

| Build | Time |
|-------|------|
| First version (dependencies downloaded into each image's own layer), one image | 13 min |
| Final version, all three images, first build | 7 min |
| Final version, all three images, after code changes | **57 s** |

Each image is 395 MB (a JRE plus the Spring Boot jar).

The old `order-service/Dockerfile` copied a jar from the local `target/` into a full JDK image and couldn't build `common-module`. It has been replaced. **`order-service/docker-compose.yml` was left untouched but is stale**: it uses ZooKeeper, a different Kafka image and its own PostgreSQL on port 5432, so it conflicts with the root compose file. It's safe to delete.

---

## 2. The full-flow system test

> **Update:** the containers now live in a shared `SystemEnvironment` class that starts PostgreSQL, Kafka and all **five** images (including api-gateway and identity-service) once, for both `OrderFlowSystemTest` and the newer `GatewaySystemTest` (see [GATEWAY-AND-AUTH.md](GATEWAY-AND-AUTH.md)).

**`system-tests/src/test/java/com/arpitha/systemtests/OrderFlowSystemTest.java`**

```text
                         private Docker network (Testcontainers)
 ┌───────────────────────────────────────────────────────────────────────────┐
 │  postgres:16  (+ docker/postgres/init.sql)      apache/kafka:3.8.0         │
 │        ▲                                          ▲                        │
 │        │                                          │                        │
 │  ecommerce/order-service  ◄──── Kafka ────►  ecommerce/inventory-service   │
 └────────▲──────────────────────────────────────────────────────▲───────────┘
          │ REST (random host ports)                               │ REST
          └──────────────────────── the test ──────────────────────┘
                       + JDBC and a Kafka consumer, to look inside
```

- **The same images compose runs.** The module runs `docker compose build order-service inventory-service` before its tests, so the test runs exactly what `docker compose up` would. It also proves the Dockerfiles work.
- **Isolated.** It has its own network, databases and Kafka, all on random host ports, so it can run while your compose stack is up. Testcontainers removes everything afterwards.
- **Black box.** Everything happens through the public REST APIs. The test only looks inside the databases and Kafka to confirm the internal mechanisms worked.
- **Logs.** Each service's container log is saved to `system-tests/target/system-test-logs/`.
- **Opt-in.** It runs with a Maven profile, so normal builds stay fast:

```bash
mvn install -Psystem-tests                    # everything, including the system test
mvn -Psystem-tests -pl system-tests verify    # just the system test (after mvn install)
```

| Test | Flow checked end to end |
|------|-------------------------|
| `orderWithinStock_IsConfirmed_AndStockIsReserved` | Order starts `CREATED` → becomes `CONFIRMED`; inventory 3 reserved, 7 available |
| `orderAboveStock_IsRejectedWithReason_AndStockIsUntouched` | `REJECTED` with "requested 8, available 5"; stock unchanged |
| `orderForProductWithoutInventory_IsRejected` | `REJECTED` with "No inventory found" |
| `cancellingConfirmedOrder_ReleasesItsStock` | `CONFIRMED` → `DELETE` → `CANCELLED`; stock back to 0 reserved, 10 available |
| `reservingLastUnits_MarksOutOfStock_AndNextOrderIsRejected` | Last 2 units → `CONFIRMED`, product `OUT_OF_STOCK`; next order `REJECTED` |
| `eventsFlowThroughOutbox_AreDeduplicatedByConsumers_AndKeepTheCorrelationId` | The `X-Correlation-ID` request header appears in the outbox event and in inventory's Kafka reply. The outbox row is published. Each consumer recorded its event in `processed_events` exactly once. |
| `invalidOrder_IsRejectedByValidation` | `POST` without `productId` → HTTP 400 |

---

## 3. Bug found by the system test: consumers missing Kafka partitions

**Symptom.** On the first system-test run, 4 of 7 tests failed: orders stayed `CREATED`. The saved logs showed order-service publishing every event, but inventory-service **received only orders 1, 5 and 7**.

**Cause.**
1. Inventory started first. Its consumer subscribed to `order.created.v1` before the topic existed, and Kafka auto-created it with the broker default of **1 partition**.
2. When order-service started, its topic declaration **grew the topic to 3 partitions**.
3. Inventory's consumer had been assigned only partition 0. Kafka clients refresh topic metadata only every 5 minutes by default, so it didn't see the new partitions.
4. Orders are keyed by order ID and spread over 3 partitions. Only those on partition 0 were processed.

It didn't show up under compose only because of startup timing: order-service happened to create the topics first. It could happen in any environment where inventory starts first.

**Fix.**
- **Each service declares every topic it touches:** the ones it publishes, the ones it consumes, and the `.DLT` topics for those, all with 3 partitions (`KafkaConfig.orderServiceTopics` / `inventoryServiceTopics`). Whichever service starts first creates them correctly, before any listener starts.
- **Consumers can't create topics** (`allow.auto.create.topics: false` in both `application.yml` files), so an accidental 1-partition topic can't appear again.
- **The inventory integration test no longer creates the order topics itself**, so it now proves inventory-service creates them.

**Verified.**
- **System test.** Inventory now starts first and is assigned `order.created.v1-0`, `-1` and `-2` straight away. All 7 tests pass, on two runs in a row.
- **Under compose, reproduced on purpose.** Kafka was recreated empty and inventory-service started alone, and `order.created.v1` was created with `PartitionCount: 3`. Then all the end-to-end scripts passed.

A second test-only problem: the system test's own JDBC connection hit PostgreSQL's rejection of the legacy JVM timezone `Asia/Calcutta`. It now calls `TimeZoneNormalizer`, just as the services do.

---

## 4. Results

| Check | Result |
|-------|--------|
| `mvn clean install` (unit + Testcontainers integration tests) | 69 tests, 0 failures, 0 skipped |
| `OrderFlowSystemTest` (real images in Docker) | 7 of 7, two runs in a row (2 to 4 minutes per run, including image builds and container start-up) |
| `mvn install -Psystem-tests` (everything, exactly as documented) | 76 tests (69 + 7), 0 failures, 0 skipped, in 5 min 12 s |
| `docker compose up -d --build` | All 6 containers up; all 3 services report `UP` at `/actuator/health`; 0 ERROR lines; services run as user `app` |
| Product-service smoke test (compose) | `POST /api/v1/products` → 201, and `ProductCreatedEvent` appears on `product.created.v1` |
| Swagger UI on 8080, 8082 and 8083 (compose) | HTTP 200 |
| `test-order-saga.ps1` against compose | 19 of 19 (before and after the partition fix) |
| `test-reliable-consumers.ps1` against compose | 17 of 17 (before and after) |
| `test-outbox-kafka-outage.ps1` against compose | 9 of 9 (before and after): the containerized order-service rode out a Kafka restart |

---

## 5. Files

| File | Status |
|------|--------|
| `order-service/Dockerfile` | Replaced: multi-stage, shared Maven cache, JRE, non-root |
| `product-service/Dockerfile`, `inventory-service/Dockerfile` | New, same design |
| `.dockerignore` | New |
| `docker-compose.yml` | Adds the three services and a Kafka health check |
| `system-tests/pom.xml` | New module (profile `system-tests`); runs `docker compose build` before its tests |
| `system-tests/.../OrderFlowSystemTest.java` | New, 7 tests |
| `pom.xml` (parent) | `system-tests` profile |
| `order-service/.../config/KafkaConfig.java`, `inventory-service/.../config/KafkaConfig.java` | Declare every topic used, including consumed and DLT topics |
| `order-service/.../application.yml`, `inventory-service/.../application.yml` | `allow.auto.create.topics: false` |
| `inventory-service/.../ReliableConsumerIntegrationTest.java` | No longer creates topics by hand |

---

## 6. Explaining it in an interview

> "Each service has a multi-stage Dockerfile. Maven builds it inside Docker with a BuildKit cache shared across images, and a non-root JRE image runs it, so one `docker compose up` starts the whole system with health-checked dependencies. On top of unit and Testcontainers integration tests, I have a black-box system test: it runs the exact images compose uses, with PostgreSQL and Kafka on a private Docker network, and drives the order saga only through the REST APIs. It checks the outcome, and also the outbox, the idempotency records and correlation-ID propagation. That test caught a real bug. Whichever service started first could let its consumer auto-create a topic with a single partition, which the other service then grew, and the consumer wouldn't see the new partitions for five minutes. The fix was for every service to declare every topic it touches, and to stop consumers from auto-creating topics."
