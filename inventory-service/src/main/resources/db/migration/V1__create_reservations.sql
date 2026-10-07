CREATE TABLE reservation (
    reservation_id VARCHAR(50) PRIMARY KEY,
    order_id VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reservation_order_id ON reservation(order_id);
CREATE INDEX idx_reservation_status ON reservation(status);
