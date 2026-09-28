-- Needed only for an orderdb created before the order saga was added.
-- Hibernate generated a CHECK constraint listing the order statuses that existed then,
-- and ddl-auto=update never changes an existing constraint, so CONFIRMED and REJECTED
-- are rejected by PostgreSQL. A freshly created database already has the full list.
--
-- Apply with:
--   docker exec -i ecommerce-postgres psql -U postgres -d orderdb < docker/postgres/migrations/001-order-status-saga.sql

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('CREATED', 'CONFIRMED', 'REJECTED', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED'));
