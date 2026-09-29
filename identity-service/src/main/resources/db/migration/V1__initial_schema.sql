-- identity-service: initial schema.
-- Hibernate only validates this schema (ddl-auto: validate); every change needs a new V<n>__*.sql.

CREATE TABLE users (
    id            UUID         NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(255),
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);
