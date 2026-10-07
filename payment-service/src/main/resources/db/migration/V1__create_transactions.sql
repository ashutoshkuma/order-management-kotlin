CREATE TABLE transaction (
    transaction_id VARCHAR(50) PRIMARY KEY,
    order_id VARCHAR(50) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_transaction_order_id ON transaction(order_id);
CREATE INDEX idx_transaction_type_status ON transaction(type, status);
CREATE UNIQUE INDEX idx_transaction_order_charge
    ON transaction(order_id, type)
    WHERE type = 'CHARGE' AND status = 'COMPLETED';
