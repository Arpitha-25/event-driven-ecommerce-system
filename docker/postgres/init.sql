-- Creates each service's database if it doesn't exist yet. Safe to run any number of times.
--  * PostgreSQL runs it automatically when the data volume is first created.
--  * The db-init service in docker-compose.yml runs it on every start, so a database added
--    later (like ecommerce_identity_db) also appears in an existing volume.
-- Each service's tables are created by its own Flyway migrations.
SELECT 'CREATE DATABASE orderdb'                WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'orderdb')\gexec
SELECT 'CREATE DATABASE ecommerce_product_db'   WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ecommerce_product_db')\gexec
SELECT 'CREATE DATABASE ecommerce_inventory_db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ecommerce_inventory_db')\gexec
SELECT 'CREATE DATABASE ecommerce_identity_db'  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ecommerce_identity_db')\gexec
