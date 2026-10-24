CREATE TABLE stores (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

INSERT INTO stores (id, name) VALUES ('DEMO-STORE', 'Demo Store');

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('CUSTOMER', 'STAFF', 'ADMIN')),
    store_id VARCHAR(100) REFERENCES stores (id),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT account_store_role_check CHECK (
        (role = 'STAFF' AND store_id IS NOT NULL)
        OR (role IN ('CUSTOMER', 'ADMIN') AND store_id IS NULL)
    )
);

CREATE TABLE security_audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_account_id UUID REFERENCES accounts (id),
    event_type VARCHAR(50) NOT NULL,
    target VARCHAR(500),
    request_path VARCHAR(500),
    session_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_security_audit_created_at ON security_audit_log (created_at);
CREATE INDEX ix_security_audit_actor_created ON security_audit_log (actor_account_id, created_at);

CREATE TABLE session_denial_counts (
    session_id VARCHAR(100) PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts (id),
    denied_count INTEGER NOT NULL CHECK (denied_count > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE security_alerts (
    id BIGSERIAL PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts (id),
    session_id VARCHAR(100) NOT NULL,
    alert_type VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    acknowledged_at TIMESTAMPTZ
);

CREATE INDEX ix_security_alerts_pending ON security_alerts (created_at)
    WHERE acknowledged_at IS NULL;

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);

ALTER TABLE orders
    ADD COLUMN customer_id UUID REFERENCES accounts (id),
    ADD COLUMN store_id VARCHAR(100) REFERENCES stores (id),
    ADD COLUMN fulfillment_status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    ADD COLUMN assigned_staff_id UUID REFERENCES accounts (id);

UPDATE orders SET store_id = 'DEMO-STORE' WHERE store_id IS NULL;

ALTER TABLE orders ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE orders DROP CONSTRAINT orders_idempotency_key_key;
ALTER TABLE orders ADD CONSTRAINT orders_customer_idempotency_key
    UNIQUE (customer_id, idempotency_key);

INSERT INTO inventory_stock (sku, on_hand, reserved)
VALUES ('DEMO-ITEM-2', 10000, 0)
ON CONFLICT (sku) DO NOTHING;
