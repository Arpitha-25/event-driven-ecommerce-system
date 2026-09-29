-- product-service: initial schema.
-- Hibernate only validates this schema (ddl-auto: validate); every change needs a new V<n>__*.sql.
-- Unique constraints use the names Hibernate generated when it managed the schema (ddl-auto: update),
-- so databases created before Flyway and databases created by Flyway are identical.

CREATE TABLE products (
    id          UUID          NOT NULL,
    sku         VARCHAR(255)  NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    description VARCHAR(255),
    price       NUMERIC(10, 2) NOT NULL,
    category    VARCHAR(255)  NOT NULL CHECK (category IN ('ELECTRONICS', 'FASHION', 'BOOKS', 'HOME', 'SPORTS')),
    status      VARCHAR(255)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'DISCONTINUED')),
    created_at  TIMESTAMP(6) WITH TIME ZONE,
    updated_at  TIMESTAMP(6) WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT ukfhmd06dsmj6k0n90swsh8ie9g UNIQUE (sku)
);
