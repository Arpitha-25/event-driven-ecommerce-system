# Database Migrations with Flyway

Each service's PostgreSQL schema is created and versioned by **Flyway**. Hibernate no longer changes the schema: it only **validates** that the JPA entities match it.

---

## 1. Why the change

Before, every service ran with `spring.jpa.hibernate.ddl-auto: update`, so Hibernate adjusted the schema at startup. That has two problems, and this project hit the first one for real:

| Problem | What happened here |
|---------|--------------------|
| `update` only **adds** things. It never changes or removes an existing column, constraint or index. | When the order statuses `CONFIRMED` and `REJECTED` were added, existing databases kept an old CHECK constraint listing only the previous statuses. Orders silently stayed `CREATED` until a hand-written SQL fix was applied (see [ORDER-INVENTORY-SAGA.md](ORDER-INVENTORY-SAGA.md), section 4). |
| Schema changes are **invisible**. | There was no record of which change reached which database, and no way to review a schema change before it happened. |

With Flyway, every schema change is a numbered SQL file in the repository. It's reviewed like code and applied exactly once, in order, to every database.

---

## 2. How it works now

```text
service starts
   │
   ├─ Flyway: compare db/migration/V*.sql with the database's flyway_schema_history table
   │      ├─ empty database           → run V1, V2, … in order
   │      ├─ database created before  → record it as version 1 ("baseline"), then run anything newer
   │      │  Flyway (by ddl-auto)
   │      └─ up to date               → nothing to do
   │
   └─ Hibernate (ddl-auto: validate): check every entity and column against the schema
          └─ mismatch → the service refuses to start, with the exact table and column
```

| File | Contents |
|------|----------|
| `order-service/src/main/resources/db/migration/V1__initial_schema.sql` | `orders`, `audit_events`, `outbox_events` (+ index for the relay), `processed_events` |
| `product-service/src/main/resources/db/migration/V1__initial_schema.sql` | `products` |
| `inventory-service/src/main/resources/db/migration/V1__initial_schema.sql` | `inventories`, `stock_reservations`, `processed_events` |

Configuration, in each service's `application.yml`:
```yaml
spring:
  flyway:
    baseline-on-migrate: true   # adopt a pre-Flyway database as version 1 instead of failing
    baseline-version: 1
  jpa:
    hibernate:
      ddl-auto: validate        # Hibernate checks the schema but never changes it
```

Dependencies: `flyway-core` and `flyway-database-postgresql`. Since Flyway 10, PostgreSQL support is a separate module.

### Changing the schema from now on
1. Add a new file, such as `V2__add_order_notes.sql`, next to `V1`. Never edit a migration that has already been applied: Flyway checksums them and will refuse to start.
2. Update the entity to match.
3. `mvn clean install`. The Testcontainers integration tests run every migration on a fresh PostgreSQL, then Hibernate validates, so a mistake fails the build.

---

## 3. How V1 was made and verified

`V1` had to match **exactly** the schema Hibernate had been creating. Otherwise new databases (built by Flyway) and existing databases (built by Hibernate, then baselined) would differ.

1. **Captured Hibernate's schema.** Each service was started against an empty database with the old `ddl-auto: update`, and Hibernate's DDL was exported as well.
2. **Wrote V1 readably,** with `id` first and comments, rather than in Hibernate's arbitrary column order.
3. **Compared the two schemas mechanically.** A catalog query lists every column (type, length, precision, scale, nullability, default, identity), every constraint (name and definition) and every index definition, sorted, so column order doesn't matter. Both databases were diffed.

| Comparison | order | product | inventory |
|------------|-------|---------|-----------|
| V1 (applied by hand) vs Hibernate's schema | identical, 40 facts | identical, 15 facts | identical, 37 facts |
| V1 vs **your existing databases** | identical, 40 facts | identical, 15 facts | identical, 37 facts |

**Two things the verification caught:**
- **A broken comparison query.** The first run reported "IDENTICAL" because the query failed and both sides were empty. The check now refuses to pass when it compares fewer than 5 facts.
- **Different constraint names.** Unique constraints differed by *name* only. A live `update` gives them Hibernate's generated names, such as `uk629g7ucwoefkvapwlhk3s7bwi`, while plain SQL gets PostgreSQL's `stock_reservations_order_id_key`. V1 uses Hibernate's names, so a future migration that refers to a constraint by name works on old and new databases alike.

---

## 4. Test results

| Scenario | Result |
|----------|--------|
| Fresh databases (Testcontainers, in `mvn clean install`) | Flyway logged `Migrating schema "public" to version "1 - initial schema"` and `Successfully applied 1 migration`; Hibernate validation passed; 69 tests pass |
| Your **existing** databases (106 orders, 45 inventory records, 119 reservations, 5 products) | `Successfully baselined schema with version: 1` → `up to date. No migration necessary`; row counts unchanged; 0 errors |
| Restart after baselining | `Current version of schema "public": 1` → `up to date. No migration necessary` |
| End-to-end scripts against the Flyway-managed services | saga 19/19, reliable consumers 17/17, Kafka outage 9/9 |
| Docker: `docker compose up -d --build` against your baselined databases | All three containers: `Current version of schema "public": 1` → `up to date`; saga 19/19 and reliable consumers 17/17 against the containers |
| Docker: the full-flow system test (fresh databases inside the test's containers) | Flyway ran `V1` inside the order-service and inventory-service containers; 7/7 tests pass |
| GitHub Actions | 69 tests + 7 system tests, 0 skipped, on every run (see [CI.md](CI.md)) |
| **Drift:** a database with `orders.status_reason` dropped | The service **refused to start**: `Schema-validation: missing column [status_reason] in table [orders]`. With `ddl-auto: update` it would have quietly re-added the column. |

---

## 5. Also changed
- **Removed `docker/postgres/migrations/001-order-status-saga.sql`.** It was the manual fix for the old status constraint. `V1` contains the full status list, and all existing databases already have it (the comparison above proves it).
- `docker/postgres/init.sql` still creates the three **databases**. Flyway creates everything **inside** them.
