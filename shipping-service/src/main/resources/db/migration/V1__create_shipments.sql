CREATE TABLE shipment (
    shipment_id VARCHAR(50) PRIMARY KEY,
    order_id VARCHAR(50) NOT NULL UNIQUE,
    tracking_number VARCHAR(50) NOT NULL,
    carrier VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMPTZ
);

CREATE INDEX idx_shipment_order_id ON shipment(order_id);
CREATE INDEX idx_shipment_status ON shipment(status);
CREATE INDEX idx_shipment_tracking ON shipment(tracking_number);
