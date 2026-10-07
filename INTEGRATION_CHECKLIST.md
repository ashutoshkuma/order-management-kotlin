# Microservices Integration Checklist

## Pre-Integration Setup

### 1. Database Preparation

- [ ] PostgreSQL running on localhost:5432
- [ ] Create databases:
  ```sql
  CREATE DATABASE inventorydb;
  CREATE DATABASE paymentdb;
  CREATE DATABASE shippingdb;
  CREATE DATABASE orderdb;  -- should already exist
  ```

- [ ] Create users with passwords:
  ```sql
  CREATE USER inventoryuser WITH PASSWORD 'inventorypass';
  CREATE USER paymentuser WITH PASSWORD 'paymentpass';
  CREATE USER shippinguser WITH PASSWORD 'shippingpass';
  
  GRANT ALL PRIVILEGES ON DATABASE inventorydb TO inventoryuser;
  GRANT ALL PRIVILEGES ON DATABASE paymentdb TO paymentuser;
  GRANT ALL PRIVILEGES ON DATABASE shippingdb TO shippinguser;
  ```

### 2. Solace PubSub+ Setup

- [ ] Solace PubSub+ broker running, SMF port on localhost:55555, SEMPv2 admin API on localhost:8085 (or configure `solace.jms.host`)
- [ ] Provision queues and topic subscriptions via the SEMPv2 REST API (services also self-provision these idempotently at startup via `SolJmsUtility`, but they can be created ahead of time for verification):
  ```bash
  # Create the durable queue for the notification-service consumer group
  curl -X POST http://localhost:8085/SEMP/v2/config/msgVpns/default/queues \
    -u admin:admin -H "Content-Type: application/json" \
    -d '{"queueName": "notification-service-group", "permission": "consume", "ingressEnabled": true, "egressEnabled": true}'

  # Add the topic subscription linking the queue to the order.events topic
  curl -X POST http://localhost:8085/SEMP/v2/config/msgVpns/default/queues/notification-service-group/subscriptions \
    -u admin:admin -H "Content-Type: application/json" \
    -d '{"subscriptionTopic": "order.events"}'
  ```

### 3. Temporal Server

- [ ] Temporal server running on localhost:7233
- [ ] Default namespace accessible
- [ ] Temporal UI on localhost:8233 (optional, for monitoring)

---

## Service Startup Order

### Phase 1: Supporting Services

1. **PostgreSQL** — All microservices need their databases
   ```bash
   # Verify connection
   psql -h localhost -U inventoryuser -d inventorydb
   ```

2. **Solace PubSub+** — Notification service needs this (no ZooKeeper-style coordination service required)
   ```bash
   # Verify the broker is up and list provisioned queues via SEMPv2
   curl -s -u admin:admin http://localhost:8085/SEMP/v2/config/msgVpns/default/queues | jq '.data[].queueName'
   ```

3. **Temporal Server** — Order service needs this
   ```bash
   # Verify connection
   curl localhost:7233  # Should not fail
   ```

### Phase 2: Downstream Services

Start in any order (they're independent of each other):

4. **Inventory Service**
   ```bash
   cd inventory-service
   mvn spring-boot:run
   # Should start on port 8081
   # Check: curl http://localhost:8081/actuator/health
   ```

5. **Payment Service**
   ```bash
   cd payment-service
   mvn spring-boot:run
   # Should start on port 8082
   # Check: curl http://localhost:8082/actuator/health
   ```

6. **Shipping Service**
   ```bash
   cd shipping-service
   mvn spring-boot:run
   # Should start on port 8083
   # Check: curl http://localhost:8083/actuator/health
   ```

### Phase 3: Event Processing Service

7. **Notification Service**
   ```bash
   cd notification-service
   mvn spring-boot:run
   # Should start on port 8084
   # Logs should show: "Subscribed to topics: [order.events]"
   ```

### Phase 4: Core Service

8. **Order Service**
   ```bash
   cd order-service
   mvn spring-boot:run
   # Should start on port 8080
   # Should connect to Temporal
   # Check: curl http://localhost:8080/actuator/health
   ```

---

## Verification Tests

### Test 1: Service Health

```bash
for port in 8080 8081 8082 8083 8084; do
  echo "Service :$port"
  curl -s http://localhost:$port/actuator/health | jq .
done
```

### Test 2: Create Order (Happy Path)

```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-123",
    "shippingAddress": "123 Main St",
    "items": [
      {"productId": "PROD-001", "quantity": 1, "price": 99.99}
    ]
  }'
```

Expected response:
```json
{
  "orderId": "ORD-...",
  "status": "CREATED",
  "version": 1
}
```

### Test 3: Add Item to Order

```bash
curl -X POST http://localhost:8080/orders/ORD-.../items \
  -H "Content-Type: application/json" \
  -d '{
    "productId": "PROD-002",
    "quantity": 2,
    "price": 49.99
  }'
```

### Test 4: Confirm Order (Start Workflow)

```bash
curl -X POST http://localhost:8080/orders/ORD-.../confirm \
  -H "Content-Type: application/json" \
  -d '{
    "totalAmount": 199.97
  }'
```

Expected response: `202 Accepted` with workflow ID

### Test 5: Monitor Workflow Status

```bash
curl http://localhost:8080/workflows/ORD-...
```

Expected response: Workflow status including saga step progress

### Test 6: Check Notification Service Logs

```bash
# Should see email notifications being sent
# [Notification] Sending welcome email for order ORD-...
# [Notification] Sending confirmation email for order ORD-...
# etc.
```

---

## Failure Mode Testing

### Test Inventory Failure (Trigger Compensation)

```bash
# Terminal 1: Stop inventory service
# (Kill the process)

# Terminal 2: Create order - should fail at inventory reservation
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{...}'

# Then confirm to trigger workflow
curl -X POST http://localhost:8080/orders/ORD-.../confirm

# Expected: Workflow shows InventoryReservationFailed event
# Compensation should be skipped (nothing to compensate yet)
```

### Test Payment Failure (Trigger Compensation)

```bash
# Set payment service to always fail
export SIMULATION_PAYMENT_FAILURE_RATE=1.0
export SIMULATION_PAYMENT_TRANSIENT_RATIO=0.0  # Non-retryable

# Restart payment service
# Create and confirm order

# Expected: 
# 1. Inventory reservation succeeds
# 2. Payment fails with CardDeclinedException
# 3. Inventory is released (compensation)
# 4. Workflow shows PaymentFailed event
```

### Test All Services Healthy

```bash
export SIMULATION_INVENTORY_FAILURE_RATE=0.0
export SIMULATION_PAYMENT_FAILURE_RATE=0.0
export SIMULATION_SHIPPING_FAILURE_RATE=0.0

# Create and confirm multiple orders - all should succeed
```

---

## Troubleshooting

### Service Won't Start

```
Error: Connection refused to inventory-service
```
**Solution:** Check that all services are running on correct ports

```bash
netstat -tlnp | grep java
# Should show: 8080, 8081, 8082, 8083, 8084
```

### Database Connection Error

```
Error: org.postgresql.util.PSQLException: Connection refused
```
**Solution:** Verify PostgreSQL is running and databases exist

```bash
psql -h localhost -U inventoryuser -d inventorydb -c "SELECT 1"
```

### Solace Connection Error

```
Error: JMSException: Connection to Solace broker failed
```
**Solution:** Verify the Solace PubSub+ broker is running and accessible

```bash
# Check broker health via the SEMPv2 REST API
curl -f -u admin:admin http://localhost:8085/SEMP/v2/monitor/about/api

# List provisioned queues on the default message-vpn
curl -s -u admin:admin http://localhost:8085/SEMP/v2/monitor/msgVpns/default/queues | jq '.data[].queueName'
```

### Temporal Connection Error

```
Error: Failed to connect to temporal service
```
**Solution:** Verify Temporal server is running

```bash
curl http://localhost:7233/health  # May not work, but server should respond
```

### Workflow Not Starting

Check order-service logs:
```bash
# Look for: "WorkflowPortAdapter: Starting fulfillment workflow"
# If not present: Order may not be in CONFIRMED state
```

---

## Monitoring

### Logs Monitoring

```bash
# Watch order service
tail -f order-service.log | grep -E "WorkflowPortAdapter|Order|Event"

# Watch inventory service
tail -f inventory-service.log | grep -E "Reservation|Reserve"

# Watch payment service
tail -f payment-service.log | grep -E "Payment|Charge"

# Watch shipping service
tail -f shipping-service.log | grep -E "Shipment|Tracking"

# Watch notification service
tail -f notification-service.log | grep -E "Notification|Email"
```

### Metrics

All services expose Prometheus metrics on `/actuator/prometheus`

```bash
curl http://localhost:8080/actuator/prometheus | grep -E "order|http|jvm"
```

### Swagger UI

- **Order Service**: http://localhost:8080/swagger-ui.html
- **Inventory**: http://localhost:8081/swagger-ui.html
- **Payment**: http://localhost:8082/swagger-ui.html
- **Shipping**: http://localhost:8083/swagger-ui.html

---

## Performance Baseline

With all services running on localhost:

| Operation | Time | Notes |
|-----------|------|-------|
| Create Order | ~50ms | Just domain event |
| Confirm Order | ~5s | Temporal workflow start + 3 activities |
| Full Order Flow | ~15s | All saga steps complete |
| Notification Email | ~100ms | Just logging in PoC |

---

## Production Readiness Checklist

- [ ] All services compile without warnings
- [ ] All services start without errors
- [ ] All health checks pass
- [ ] Happy path workflow completes end-to-end
- [ ] Saga compensation works (inventory release on payment failure)
- [ ] Notifications send for all events
- [ ] Workflow status queries respond
- [ ] Signal methods work (cancel order, retry payment)
- [ ] Metrics are being collected
- [ ] Logs are structured and searchable

---

## Next Steps After Successful Integration

1. **Write Integration Tests** — Use Testcontainers for all services
2. **Load Testing** — Verify performance under concurrent orders
3. **Chaos Testing** — Kill services mid-workflow, verify recovery
4. **Security Testing** — SQL injection, authorization, rate limiting
5. **Documentation** — API docs, deployment guides, runbooks
6. **Deployment** — Docker Compose, Kubernetes manifests
7. **Monitoring** — Prometheus + Grafana dashboards
8. **CI/CD** — GitHub Actions or GitLab CI for automated builds/tests

---

## Quick Start Script

```bash
#!/bin/bash
set -e

echo "🚀 Starting Order Management System..."

# Assume PostgreSQL, Solace, Temporal already running

echo "Starting Inventory Service..."
cd inventory-service && mvn spring-boot:run &
sleep 5

echo "Starting Payment Service..."
cd ../payment-service && mvn spring-boot:run &
sleep 5

echo "Starting Shipping Service..."
cd ../shipping-service && mvn spring-boot:run &
sleep 5

echo "Starting Notification Service..."
cd ../notification-service && mvn spring-boot:run &
sleep 5

echo "Starting Order Service..."
cd ../order-service && mvn spring-boot:run &
sleep 5

echo "✅ All services started!"
echo ""
echo "Service URLs:"
echo "  Order Service: http://localhost:8080/swagger-ui.html"
echo "  Inventory: http://localhost:8081/swagger-ui.html"
echo "  Payment: http://localhost:8082/swagger-ui.html"
echo "  Shipping: http://localhost:8083/swagger-ui.html"
echo "  Notification: http://localhost:8084"
echo ""
echo "Kill with: pkill -f spring-boot:run"
```

Save as `start-all-services.sh` and run:
```bash
chmod +x start-all-services.sh
./start-all-services.sh
```

---

**Status: Ready for Integration Testing ✅**
